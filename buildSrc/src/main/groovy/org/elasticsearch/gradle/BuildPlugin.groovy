package org.elasticsearch.gradle

import com.carrotsearch.gradle.randomizedtesting.RandomizedTestingTask
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.compile.JavaCompile

/**
 * Encapsulates build configuration for elasticsearch projects.
 */
class BuildPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply('java')
        project.pluginManager.apply('carrotsearch.randomizedtesting')
        project.pluginManager.apply('de.thetaphi.forbiddenapis')

        configureCompile(project)

        // configure test, and create integTest task as well
        Closure testConfig = createSharedTestConfig(project)
        RandomizedTestingTask test = configureTest(project.tasks, testConfig)
        RandomizedTestingTask integTest = configureIntegTest(project.tasks, getIntegTestClass(), test, testConfig)

        // add hack to un-jar rest spec tests/api as test resources
        /*Task restSpecHack = RestSpecHack.setup(project, true)
        test.dependsOn(restSpecHack)
        integTest.dependsOn(restSpecHack)*/

        // enforce order of check tasks: precommit, test, integTest
        Task precommit = configurePrecommit(project)
        test.mustRunAfter(precommit)
        integTest.mustRunAfter(precommit)
        project.tasks.findByName('check').dependsOn(precommit, integTest) // test is already a dep
    }

    // overridable by subclass plugins
    Class<? extends RandomizedTestingTask> getIntegTestClass() {
        return RandomizedTestingTask
    }

    /** Adds compiler settings to the project */
    static void configureCompile(Project project) {
        project.gradle.projectsEvaluated {
            // fail on all javac warnings
            project.tasks.withType(JavaCompile) {
                options.compilerArgs << '-Werror' << '-Xlint:all' << '-Xdoclint:all/private' << '-Xdoclint:-missing'
            }
        }
    }

    /** Returns a closure of common configuration shared by unit and integration tests. */
    static Closure createSharedTestConfig(Project project) {
        return {
            // TODO: don't use JAVA_HOME env var, but instead sysprop set by java?
            jvm System.getenv('JAVA_HOME') + File.separator + 'bin' + File.separator + 'java'
            parallelism System.getProperty('tests.jvms', 'auto')

            // TODO: why are we not passing maxmemory to junit4?
            jvmArg '-Xmx' + System.getProperty('tests.heap.size', '512m')
            jvmArg '-Xms' + System.getProperty('tests.heap.size', '512m')
            if (JavaVersion.current().isJava7()) {
                // some tests need a large permgen, but that only exists on java 7
                jvmArg '-XX:MaxPermSize=128m'
            }
            jvmArg '-XX:MaxDirectMemorySize=512m'
            jvmArg '-XX:+HeapDumpOnOutOfMemoryError'
            File heapdumpDir = new File(project.buildDir, 'heapdump')
            heapdumpDir.mkdirs()
            jvmArg '-XX:HeapDumpPath=' + heapdumpDir

            // we use './temp' since this is per JVM and tests are forbidden from writing to CWD
            sysProp 'java.io.tmpdir', './temp'
            sysProp 'java.awt.headless', 'true'
            sysProp 'tests.maven', 'true' // TODO: rename this once we've switched to gradle!
            sysProp 'tests.task', path
            sysProp 'tests.security.manager', 'true'
            // default test sysprop values
            sysProp 'tests.ifNoTests', 'fail'
            sysProp 'es.logger.level', 'WARN'
            copySysPropPrefix 'tests.'
            copySysPropPrefix 'es.'

            // System assertions (-esa) are disabled for now because of what looks like a
            // JDK bug triggered by Groovy on JDK7. We should look at re-enabling system
            // assertions when we upgrade to a new version of Groovy (currently 2.4.4) or
            // require JDK8. See https://issues.apache.org/jira/browse/GROOVY-7528.
            enableSystemAssertions false

            testLogging {
                slowTests {
                    heartbeat 10
                    summarySize 5
                }
                stackTraceFilters {
                    // custom filters: we carefully only omit test infra noise here
                    contains '.SlaveMain.'
                    regex(/^(\s+at )(org\.junit\.)/)
                    // also includes anonymous classes inside these two:
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.RandomizedRunner)/)
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.ThreadLeakControl)/)
                    regex(/^(\s+at )(com\.carrotsearch\.randomizedtesting\.rules\.)/)
                    regex(/^(\s+at )(org\.apache\.lucene\.util\.TestRule)/)
                    regex(/^(\s+at )(org\.apache\.lucene\.util\.AbstractBeforeAfterRule)/)
                }
            }

            balancers {
                executionTime cacheFilename: ".local-${project.version}-${name}-execution-times.log"
            }

            listeners {
                junitReport()
            }

            exclude '**/*$*.class'
        }
    }

    /** Configures the test task */
    static Task configureTest(TaskContainer tasks, Closure testConfig) {
        Task test = tasks.getByName('test')
        test.configure(testConfig)
        test.configure {
            include '**/*Tests.class'
        }
        return test
    }

    /** Adds and configures an integration test task */
    static Task configureIntegTest(TaskContainer tasks, Class integTestClass, RandomizedTestingTask test, Closure testConfig) {
        Map integTestOptions = [
            'name': 'integTest',
            'type': integTestClass,
            'group': JavaBasePlugin.VERIFICATION_GROUP,
            'description': 'Tests integration of elasticsearch components'
        ]
        RandomizedTestingTask integTest = tasks.create(integTestOptions)
        integTest.classpath = test.classpath
        integTest.testClassesDir = test.testClassesDir
        integTest.dependsOn = test.dependsOn
        integTest.configure(testConfig)
        integTest.configure {
            include '**/*IT.class'
        }
        integTest.mustRunAfter(test)
        return integTest
    }

    /** Adds a precommit task which depends on non-test check tasks. */
    static Task configurePrecommit(Project project) {
        List precommitTasks = [
                configureForbiddenApis(project),
                configureForbiddenPatterns(project.tasks)]

        Map precommitOptions = [
                name: 'precommit',
                group: JavaBasePlugin.VERIFICATION_GROUP,
                description: 'Runs all non test checks, useful for running before committing.',
                dependsOn: precommitTasks
        ]
        return project.tasks.create(precommitOptions)
    }

    static Task configureForbiddenApis(Project project) {
        project.forbiddenApis {
            internalRuntimeForbidden = true
            failOnUnsupportedJava = false
            bundledSignatures = ['jdk-unsafe', 'jdk-deprecated']
            signaturesFiles = project.files(new File(getClass().getResource('/forbidden/all-signatures.txt').toURI()))
            suppressAnnotations = ['**.SuppressForbidden']
        }
        project.tasks.findByName('forbiddenApisMain').configure {
            bundledSignatures += ['jdk-system-out']
            signaturesFiles += project.files(
                    new File(getClass().getResource('/forbidden/core-signatures.txt').toURI()),
                    new File(getClass().getResource('/forbidden/third-party-signatures.txt').toURI()))
        }
        project.tasks.findByName('forbiddenApisTest').configure {
            signaturesFiles += project.files(new File(getClass().getResource('/forbidden/test-signatures.txt').toURI()))
        }
        Task forbiddenApis = project.tasks.findByName('forbiddenApis')
        forbiddenApis.group = "" // clear group, so this does not show up under verification tasks
        return forbiddenApis
    }

    static Task configureForbiddenPatterns(TaskContainer tasks) {
        def options = [
            'name': 'forbiddenPatterns',
            'type': ForbiddenPatternsTask,
            'description': 'Checks source files for invalid patterns like nocommits or tabs',
        ]
        return tasks.create(options) {
            rule name: 'nocommit', pattern: /nocommit/
            rule name: 'tab', pattern: /\t/
        }
    }
}
