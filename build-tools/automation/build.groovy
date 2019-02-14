// This file is based on the Jenkins scripted pipeline (as opposed to the declarative pipeline) syntax
// https://jenkins.io/doc/book/pipeline/syntax/#scripted-pipeline

def XADir = "xamarin-android"

def publishBuildFilePaths = null
def publishTestFilePaths = null

def MSBUILD_AUTOPROVISION_ARGS="/p:AutoProvision=True /p:AutoProvisionUsesSudo=True /p:IgnoreMaxMonoVersion=False"

def isPr = false                // Default to CI

def stageStatus = 0

def processBuildStatus() {
    def status = 0

    try {
        echo "processing build status"
        sh "make package-build-status CONFIGURATION=${env.BuildFlavor}"
    } catch (error) {
        echo "ERROR : NON-FATAL : processBuildStatus: Unexpected error: ${error}"
        status = 1
    }

    return status
}

def publishPackages(filePaths) {
    def status = 0
    try {
         // Note: The following function is provided by the Azure Blob Jenkins plugin
         azureUpload(storageCredentialId: "${env.StorageCredentialId}",
                                storageType: "blobstorage",
                                containerName: "${env.ContainerName}",
                                virtualPath: "${env.StorageVirtualPath}",
                                filesPath: "${filePaths}",
                                allowAnonymousAccess: true,
                                pubAccessible: true,
                                doNotWaitForPreviousBuild: true,
                                uploadArtifactsOnlyIfSuccessful: true)
    } catch (error) {
        echo "ERROR : publishPackages: Unexpected error: ${error}"
        status = 1
    }

    return status
}

timestamps {
    node("${env.BotLabel}") {
        def scmVars

        stageStatus = 0
        try {
            stage('checkout') {
                timeout(time: 60, unit: 'MINUTES') {    // Time ranges from seconds to minutes depending on how many changes need to be brought down
                    dir(XADir) {
                        scmVars = checkout scm
                    }

                    echo "Stage result: checkout: ${currentBuild.currentResult}"
                }
            }
        } catch (error) {
            echo "ERROR : checkout: Unexpected error: ${error}"
            currentBuild.result = 'FAILURE'
        }

        stageStatus = 0
        try {
            stage('init') {
                timeout(time: 30, unit: 'SECONDS') {    // Typically takes less than a second
                    // Note: PR plugin environment variable settings available here: https://wiki.jenkins.io/display/JENKINS/GitHub+pull+request+builder+plugin
                    isPr = env.ghprbActualCommit != null
                    def branch = isPr ? env.GIT_BRANCH : scmVars.GIT_BRANCH
                    def commit = isPr ? env.ghprbActualCommit : scmVars.GIT_COMMIT

                    def buildType = isPr ? 'PR' : 'CI'

                    echo "Job: ${env.JOB_BASE_NAME}"
                    echo "Branch: ${branch}"
                    echo "Commit: ${commit}"
                    echo "Build type: ${buildType}"
                    if (isPr) {
                        echo "PR id: ${env.ghprbPullId}"
                        echo "PR link: ${env.ghprbPullLink}"
                    }

                    publishBuildFilePaths = "xamarin-android/xamarin.android-oss*.zip,xamarin-android/bin/${env.BuildFlavor}/bundle-*.zip,xamarin-android/bin/Build*/Xamarin.Android.Sdk*.vsix,xamarin-android/prepare-image-dependencies.sh,xamarin-android/build-status*,xamarin-android/xa-build-status*";
                    publishTestFilePaths = "xamarin-android/xa-test-errors*"

                    echo "Stage result: init: ${currentBuild.currentResult}"
                }
            }
        } catch (error) {
            echo "ERROR : init: Unexpected error: ${error}"
            currentBuild.result = 'FAILURE'
        }

        stageStatus = 0
        try {
            stage('clean') {
                timeout(time: 30, unit: 'SECONDS') {    // Typically takes less than a second
                    dir('xamarin-android_tmp') {
                        deleteDir()
                    }

                    echo "Stage result: clean: ${currentBuild.currentResult}"
                }
            }
        } catch (error) {
            echo "ERROR : clean: Unexpected error: ${error}"
            currentBuild.result = 'FAILURE'
        }

        stageStatus = 0
        try {
            stage('prepare deps') {
                timeout(time: 30, unit: 'MINUTES') {    // Typically takes less than 2 minutes

                    dir(XADir) {
                        sh "make prepare-deps CONFIGURATION=${env.BuildFlavor} MSBUILD_ARGS='$MSBUILD_AUTOPROVISION_ARGS'"
                    }

                    echo "Stage result: prepare deps: ${currentBuild.currentResult}"
                }
            }
        } catch (error) {
            echo "ERROR : prepare deps: Unexpected error: ${error}"
            currentBuild.result = 'FAILURE'
        }

        stageStatus = 0
        try {
            stage('build') {
                timeout(time: 6, unit: 'HOURS') {    // Typically takes less than one hour except a build on a new bot to populate local caches can take several hours
                    dir(XADir) {
                        script {
                            if (isPr) {
                                echo "PR build definition detected: building with 'make all'"
                                sh "make all CONFIGURATION=${env.BuildFlavor} MSBUILD_ARGS='$MSBUILD_AUTOPROVISION_ARGS'"
                            } else {
                                echo "PR build definition *not* detected: building with 'make jenkins'"
                                sh "make jenkins CONFIGURATION=${env.BuildFlavor} MSBUILD_ARGS='$MSBUILD_AUTOPROVISION_ARGS'"
                            }
                        }
                    }

                    echo "Stage result: build: ${currentBuild.currentResult}"
                }
            }
        } catch (error) {
            echo "ERROR : build: Unexpected error: ${error}"
            currentBuild.result = 'FAILURE'
        }

        stageStatus = 0
        try {
            stage('create vsix') {
                timeout(time: 30, unit: 'MINUTES') {    // Typically takes less than 5 minutes
                    dir(XADir) {
                        sh "make create-vsix CONFIGURATION=${env.BuildFlavor}"
                    }

                    echo "Stage result: create vsix: ${currentBuild.currentResult}"
                }
            }
        } catch (error) {
            echo "ERROR : create vsix: Unexpected error: ${error}"
            currentBuild.result = 'FAILURE'
        }

        stageStatus = 0
        try {
            stage('build tests') {
                timeout(time: 30, unit: 'MINUTES') {    // Typically takes less than 10 minutes
                    dir(XADir) {
                        sh "make all-tests CONFIGURATION=${env.BuildFlavor}"
                    }

                    echo "Stage result: build tests: ${currentBuild.currentResult}"
                }
            }
        } catch (error) {
            echo "ERROR : build tests: Unexpected error: ${error}"
            currentBuild.result = 'FAILURE'
        }

        stageStatus = 0
        try {
            stage('process build results') {
                timeout(time: 10, unit: 'MINUTES') {    // Typically takes less than a minute
                    dir(XADir) {
                        processBuildStatus()
                    }

                    echo "Stage result: process build results: ${currentBuild.currentResult}"
                }
            }
        } catch (error) {
            echo "ERROR : process build results: Unexpected error: ${error}"
            currentBuild.result = 'FAILURE'
        }

        stageStatus = 0
        try {
            stage('publish packages to Azure') {
                timeout(time: 10, unit: 'MINUTES') {    // Typically takes less than a minute
                    echo "publishBuildFilePaths: ${publishBuildFilePaths}"
                    stageStatus = publishPackages(publishBuildFilePaths)

                    if (stageStatus != 0) {
                        error "publish packages to Azure FAILED"    // Ensure stage is labeled as 'failed' and red failure indicator is displayed in Jenkins pipeline steps view
                    }
                }
            }
        } catch (error) {
            echo "ERROR : package publish: Unexpected error: ${error} / status ${stageStatus}. Marking build as UNSTABLE"
            currentBuild.result = 'UNSTABLE'
        } finally {
            echo "Stage result: publish packages to Azure: ${currentBuild.currentResult}"
        }

        stageStatus = 0
        try {
            stage('run all tests') {
                timeout(time: 160, unit: 'MINUTES') {   // Typically takes 1hr and 50 minutes (or 110 minutes)
                    dir(XADir) {
                        echo "running tests"
                        stageStatus = sh(
                            script: "make run-all-tests CONFIGURATION=${env.BuildFlavor}",
                            returnStatus: true
                        );

                        if (stageStatus != 0) {
                            error "run-all-tests FAILED"     // Ensure stage is labeled as 'failed' and red failure indicator is displayed in Jenkins pipeline steps view
                        }
                    }
                }
            }
        } catch (error) {
            echo "ERROR : test run: Unexpected error: ${error} / status ${stageStatus}. Marking build as UNSTABLE"
            currentBuild.result = 'UNSTABLE'
        } finally {
            echo "Stage result: run all tests: current build result: ${currentBuild.currentResult} / stageStatus: ${stageStatus}"
        }

        stageStatus = 0
        try {
            stage('publish test error logs to Azure') {
                timeout(time: 10, unit: 'MINUTES') {  // Typically takes less than a minute
                    dir(XADir) {
                        echo "packaging test error logs"
                        sh "make package-test-errors"
                    }

                    echo "publishTestFilePaths: ${publishTestFilePaths}"
                    stageStatus = publishPackages(publishTestFilePaths)
                }
            }
        } catch (error) {
            echo "ERROR : publish test error logs: Unexpected error: ${error} / status: ${stageStatus}. Marking build as UNSTABLE"
            currentBuild.result = 'UNSTABLE'
        } finally {
            echo "Stage result: publish test error logs to Azure: ${currentBuild.currentResult}"
        }

        stageStatus = 0
        try {
            stage('Plot build & test metrics') {
                timeout(time: 30, unit: 'SECONDS') {    // Typically takes less than a second
                    dir(XADir) {
                        plot(
                            title: 'Jcw',
                            csvFileName: 'plot-jcw-test-times.csv',
                            csvSeries: [[
                                displayTableFlag: true, file: 'TestResult-Xamarin.Android.JcwGen_Tests-times.csv', inclusionFlag: 'OFF'
                            ]],
                            group: 'Tests times', logarithmic: true, style: 'line', yaxis: 'ms'
                        )
                        plot(
                            title: 'Locale',
                            csvFileName: 'plot-locale-times.csv',
                            csvSeries: [[
                                displayTableFlag: true, file: 'TestResult-Xamarin.Android.Locale_Tests-times.csv', inclusionFlag: 'OFF'
                            ]],
                            group: 'Tests times', logarithmic: true, style: 'line', yaxis: 'ms'
                        )
                        plot(
                            title: 'Runtime test sizes',
                            csvFileName: 'plot-runtime-test-sizes.csv',
                            csvSeries: [[
                                displayTableFlag: true, file: 'TestResult-Mono.Android_Tests-values.csv', inclusionFlag: 'OFF'
                            ]],
                            group: 'Tests size', logarithmic: true, style: 'line', yaxis: 'ms'
                        )
                        plot(
                            title: 'Runtime merged',
                            csvFileName: 'plot-runtime-merged-test-times.csv',
                            csvSeries: [[
                                displayTableFlag: true, file: 'TestResult-Mono.Android_Tests-times.csv', inclusionFlag: 'OFF'
                            ]],
                            group: 'Tests times', logarithmic: true, style: 'line', yaxis: 'ms'
                        )
                        plot(
                            title: 'Xamarin.Forms app startup',
                            csvFileName: 'plot-xamarin-forms-startup-test-times.csv',
                            csvSeries: [[
                                displayTableFlag: true, file: 'TestResult-Xamarin.Forms_Test-times.csv', inclusionFlag: 'OFF'
                            ]],
                            group: 'Tests times', logarithmic: true, style: 'line', yaxis: 'ms'
                        )
                        plot(
                            title: 'Xamarin.Forms app',
                            csvFileName: 'plot-xamarin-forms-tests-size.csv',
                            csvSeries: [[
                                displayTableFlag: true, file: 'TestResult-Xamarin.Forms_Tests-values.csv', inclusionFlag: 'OFF'
                            ]],
                            group: 'Tests size', logarithmic: true, style: 'line', yaxis: 'ms'
                        )

                        plot(
                            title: 'Hello World',
                            csvFileName: 'plot-hello-world-build-times.csv',
                            csvSeries: [[
                                displayTableFlag: true, file: 'TestResult-Timing-HelloWorld.csv', inclusionFlag: 'OFF'
                            ]],
                            group: 'Build times', logarithmic: true, style: 'line', yaxis: 'ms'
                        )

                        plot(
                            title: 'Xamarin.Forms',
                            csvFileName: 'plot-xamarin-forms-integration-build-times.csv',
                            csvSeries: [[
                                displayTableFlag: true, file: 'TestResult-Timing-Xamarin.Forms-Integration.csv', inclusionFlag: 'OFF'
                            ]],
                            group: 'Build times', logarithmic: true, style: 'line', yaxis: 'ms'
                        )
                    }
                }
            }
        } catch (error) {
            echo "ERROR : Plot build & test metrics: Unexpected error: ${error}. Marking build as UNSTABLE"
            currentBuild.result = 'UNSTABLE'
        } finally {
            echo "Stage result: Plot build & test metrics: ${currentBuild.currentResult}"
        }

        stageStatus = 0
        try {
            stage('Publish test results') {
                timeout(time: 5, unit: 'MINUTES') {    // Typically takes under 1 minute to publish test results
                    xunit thresholds: [
                            failed(unstableNewThreshold: '0', unstableThreshold: '0'),
                            skipped()                                                       // Note: Empty threshold settings per settings in the xamarin-android freestyle build are not permitted here
                        ],
                        tools: [
                            NUnit2(deleteOutputFiles: true,
                            failIfNotNew: true,
                            pattern: 'xamarin-android/TestResult-*.xml',
                            skipNoTestFiles: true,
                            stopProcessingIfError: false)
                        ]

                    if (currentBuild.currentResult == 'UNSTABLE') {
                        error "One or more tests failed"                // Force an error condition if there was a test failure to indicate that this stage was the source of the build failure
                    }
                }
            }
        } catch (error) {
            echo "ERROR : Publish test results: Unexpected error: ${error}. Marking build as UNSTABLE"
            currentBuild.result = 'UNSTABLE'
        } finally {
            echo "Stage result: Publish test results: ${currentBuild.currentResult}"
        }
    }
}
