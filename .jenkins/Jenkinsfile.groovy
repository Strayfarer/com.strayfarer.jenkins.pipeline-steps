def assertValue(actual, expected, description) {
    if (actual != expected) {
        error "${description}: expected '${expected}', got '${actual}'"
    }
}

def testOutputFiles(Closure execute, Closure status, Closure capture) {
    dir('command-output-files') {
        assertValue(status(script: 'exit 7', stdout: '', stderr: null), 7, 'empty output path is unset')
        assertValue(capture(script: 'echo empty-path-output', stdout: null, stderr: ''),
            'empty-path-output', 'null output path is unset')
        def outputCommand = isUnix()
            ? "printf '  file-out  '; printf '  file-err  ' >&2; exit 7"
            : "[Console]::Out.Write('  file-out  '); [Console]::Error.Write('  file-err  '); exit 7"
        assertValue(status(script: outputCommand, stdout: 'nested/out.txt', stderr: 'other/deep/err.txt'),
            7, 'file output preserves nonzero status')
        assertValue(readFile('nested/out.txt'), '  file-out  ', 'stdout file preserves whitespace')
        assertValue(readFile('other/deep/err.txt'), '  file-err  ', 'stderr file preserves whitespace')
        def combinedCommand = isUnix()
            ? "printf 'combined-out|'; printf 'combined-err|' >&2"
            : "[Console]::Out.Write('combined-out|'); [Console]::Error.Write('combined-err|')"
        writeFile(file: 'combined.txt', text: 'stale')
        assertValue(capture(script: combinedCommand, stdout: 'combined.txt', stderr: './combined.txt'),
            'combined-out|', 'combined file does not change stdout return')
        def combined = readFile('combined.txt')
        assertValue(combined.contains('combined-out|') && combined.contains('combined-err|') && !combined.contains('stale'),
            true, 'shared file contains both streams and overwrites previous content')
        execute(script: 'echo single-output', stdout: 'single.txt')
        assertValue(readFile('single.txt').trim(), 'single-output', 'exec supports stdout file')
        def failed = false
        try {
            execute(script: outputCommand, stdout: 'failed/out.txt', stderr: 'failed/err.txt')
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            throw e
        } catch (hudson.AbortException e) {
            failed = true
        }
        assertValue(failed, true, 'exec still fails on nonzero exit')
        assertValue(readFile('failed/out.txt'), '  file-out  ', 'failed command retains stdout')
        assertValue(readFile('failed/err.txt'), '  file-err  ', 'failed command retains stderr')
        dir('existing-directory') {
            writeFile(file: 'keep.txt', text: 'directory fixture')
        }
        def invalidDestination = false
        try {
            status(script: 'echo should-not-succeed', stdout: 'existing-directory')
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            throw e
        } catch (java.io.IOException expected) {
            invalidDestination = true
        }
        assertValue(invalidDestination, true, 'directory output fails even with execStatus')

        dir('streaming') {
            deleteDir()
            def streamingCommand = isUnix()
                ? "printf 'early-out'; printf 'early-err' >&2; while [ ! -f release ]; do sleep 1; done; exit 7"
                : "[Console]::Out.Write('early-out'); [Console]::Error.Write('early-err'); while (!(Test-Path -LiteralPath release)) { Start-Sleep -Milliseconds 100 }; exit 7"
            timeout(time: 2, unit: 'MINUTES') {
                parallel(
                    writer: {
                        assertValue(status(script: streamingCommand, stdout: 'out.txt', stderr: 'err.txt'),
                            7, 'streamed command retains exit status')
                    },
                    reader: {
                        waitUntil(initialRecurrencePeriod: 100, quiet: true) {
                            fileExists('out.txt') && fileExists('err.txt') &&
                                readFile('out.txt') == 'early-out' && readFile('err.txt') == 'early-err'
                        }
                        writeFile(file: 'release', text: 'output observed before process exit')
                    }
                )
            }
        }
    }
}

def withTestDockerContainer(Closure body) {
    def agentContainer = execStdout('hostname')
    def image = 'faulo/compose-unity:latest'
    def sidecarCommand = isUnix()
        ? 'sleep 3600'
        : "pwsh -NoLogo -NoProfile -NonInteractive -Command 'Start-Sleep -Seconds 3600'"
    def containerId = execStdout "docker run -d --label net.slothsoft.test=pipeline-steps --volumes-from ${agentContainer} ${image} ${sidecarCommand}"
    try {
        body(containerId)
    } finally {
        withEnv(["PIPELINE_TEST_CONTAINER=${containerId}"]) {
            if (isUnix()) {
                exec 'docker rm -f --volumes "$PIPELINE_TEST_CONTAINER"'
            } else {
                exec 'docker rm -f --volumes $env:PIPELINE_TEST_CONTAINER'
            }
        }
    }
}

def testNodes = ['linux && server', 'windows && server']
for (int testIndex = 0; testIndex < testNodes.size(); testIndex++) {
    def testNode = testNodes[testIndex]
    stage("Agent: ${testNode}") {
        node(testNode) {
            def bookkeepingCommand = isUnix()
                ? "find . -maxdepth 1 -name '.pipeline-*' -print"
                : "Get-ChildItem -Force -Name -Filter '.pipeline-*' -ErrorAction SilentlyContinue"
            def testImage = isUnix()
                ? 'alpine:latest'
                : 'mcr.microsoft.com/powershell:lts-nanoserver-1809'

            stage('Host command steps') {
                assertValue(isWindows(), !isUnix(), 'isWindows on host')
                exec 'echo host-exec-ok'
                assertValue(execStatus('exit 7'), 7, 'execStatus on host')
                assertValue(execStdout('echo host-stdout-ok'), 'host-stdout-ok', 'execStdout on host')
                testOutputFiles({ options -> exec(options) }, { options -> execStatus(options) }, { options -> execStdout(options) })
            }

            stage('Command bookkeeping') {
                dir('bookkeeping-repository') {
                    withEnv(["WORKSPACE_TMP=${pwd()}/missing-temp"]) {
                        def files = execStdout bookkeepingCommand
                        assertValue(files, '', 'host command bookkeeping outside current directory')
                    }
                }
            }

            stage('Dotenv scope') {
                writeFile file: 'pipeline.env', text: 'DOTENV_CRLF=parsed\r\nDOTENV_EMPTY=\r\nDOTENV_QUOTED="value # retained" # comment\r\n'
                writeFile file: 'pipeline-empty.env', text: ''
                env.DOTENV_CRLF = 'outer'

                withEnvFile('pipeline.env') {
                    assertValue(env.DOTENV_CRLF, 'parsed', 'CRLF dotenv value')
                    assertValue(env.DOTENV_EMPTY, '', 'empty dotenv value')
                    assertValue(env.DOTENV_QUOTED, 'value # retained', 'quoted dotenv value')
                }
                assertValue(env.DOTENV_CRLF, 'outer', 'dotenv scope restoration')
                assertValue(env.DOTENV_QUOTED, null, 'dotenv variable removal')

                withEnvFile('pipeline-empty.env') {
                    echo 'empty-dotenv-body-ran'
                }
            }

            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                stage('docker.image.inside exec') {
                    docker.image(testImage).inside {
                        exec 'echo container-exec-ok'
                    }
                }
            }

            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                stage('docker.image.inside execStatus') {
                    docker.image(testImage).inside {
                        assertValue(execStatus('exit 9'), 9, 'execStatus in container')
                    }
                }
            }

            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                stage('docker.image.inside execStdout') {
                    docker.image(testImage).inside {
                        assertValue(execStdout('echo container-stdout-ok'), 'container-stdout-ok', 'execStdout in container')
                        def files = execStdout bookkeepingCommand
                        assertValue(files, '', 'container command bookkeeping outside current directory')
                    }
                }
            }

            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                stage('insideDockerContainer exec') {
                    withTestDockerContainer { containerId ->
                        insideDockerContainer(containerId) {
                            exec 'echo container-exec-ok'
                        }
                    }
                }
            }

            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                stage('insideDockerContainer execStatus') {
                    withTestDockerContainer { containerId ->
                        insideDockerContainer(containerId) {
                            assertValue(execStatus('exit 9'), 9, 'execStatus in container')
                        }
                    }
                }
            }

            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                stage('insideDockerContainer execStdout') {
                    withTestDockerContainer { containerId ->
                        insideDockerContainer(containerId) {
                            assertValue(execStdout('echo container-stdout-ok'), 'container-stdout-ok', 'execStdout in container')
                            def files = execStdout bookkeepingCommand
                            assertValue(files, '', 'container command bookkeeping outside current directory')
                            testOutputFiles({ options -> exec(options) }, { options -> execStatus(options) }, { options -> execStdout(options) })
                        }
                    }
                }
            }

            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                stage('connectToDockerContainer commands') {
                    withTestDockerContainer { containerId ->
                        def container = connectToDockerContainer(containerId)
                        exec 'echo host-command-outside-container'
                        container.exec 'echo connected-container-exec-ok'
                        assertValue(container.execStatus('exit 9'), 9, 'connected container execStatus')
                        assertValue(container.execStdout('echo connected-container-stdout-ok'),
                            'connected-container-stdout-ok', 'connected container execStdout')
                        testOutputFiles({ options -> container.exec(options) }, { options -> container.execStatus(options) },
                            { options -> container.execStdout(options) })
                    }
                }
            }

            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                stage('Docker container label selectors') {
                    withTestDockerContainer { firstId ->
                        withTestDockerContainer { secondId ->
                            def sidecar = connectToDockerContainer(label: ['net.slothsoft.test': 'pipeline-steps'])
                            sidecar.exec 'echo label-connected-exec-ok'
                            assertValue(sidecar.execStatus('exit 8'), 8, 'label-connected execStatus')
                            assertValue(sidecar.execStdout('echo label-connected-stdout-ok'),
                                'label-connected-stdout-ok', 'label-connected execStdout')
                            insideDockerContainer(label: ['net.slothsoft.test': 'pipeline-steps']) {
                                exec 'echo label-scoped-exec-ok'
                            }
                        }
                    }
                }
            }

            stage('Deferred Docker container validation') {
                def missing = connectToDockerContainer('pipeline-steps-missing-container')
                insideDockerContainer('pipeline-steps-missing-container') {
                    echo 'Missing container scope entered without inspection'
                }
                try {
                    missing.exec 'echo unexpected'
                    error 'Missing container command unexpectedly succeeded'
                } catch (hudson.AbortException expected) {
                    assertValue(expected.message.contains('does not exist or cannot be inspected'),
                        true, 'missing container fails at command execution')
                }
                def missingLabel = connectToDockerContainer(label: ['net.slothsoft.test': 'pipeline-steps-missing'])
                insideDockerContainer(label: ['net.slothsoft.test': 'pipeline-steps-missing']) {
                    echo 'Missing label scope entered without inspection'
                }
                try {
                    missingLabel.exec 'echo unexpected'
                    error 'Missing label command unexpectedly succeeded'
                } catch (hudson.AbortException expected) {
                    assertValue(expected.message.contains('No running Docker container has label'),
                        true, 'missing label fails at command execution')
                }
            }

            stage('Current-node everyNode') {
                def currentNode = env.NODE_NAME
                everyNode(env.NODE_NAME, false, true) {
                    assertValue(env.NODE_NAME, currentNode, 'current-node everyNode')
                    assertValue(env.STAGE_NAME, env.NODE_NAME, 'current-node everyNode stage')
                    echo "current-node-visited=${env.NODE_NAME}"
                }
            }

            stage('Current-node conditional allocation') {
                def currentNode = env.NODE_NAME
                def currentWorkspace = pwd()
                def result = steps.nodeIfCurrentDoesNotMatch('server') {
                    assertValue(env.NODE_NAME, currentNode, 'conditional current node')
                    assertValue(pwd(), currentWorkspace, 'conditional current workspace')
                    return 'reused'
                }
                assertValue(result, 'reused', 'conditional current-node result')
            }
        }
    }
}

stage('Queued conditional allocation') {
    def result = steps.nodeIfCurrentDoesNotMatch('server') {
        echo "conditional-node=${env.NODE_NAME}"
        return 'allocated'
    }
    assertValue(result, 'allocated', 'conditional allocated-node result')
}

stage('Queued everyNode') {
    everyNode('server') {
        assertValue(env.STAGE_NAME, env.NODE_NAME, 'queued everyNode stage')
        echo "queued-node-visited=${env.NODE_NAME}"
    }
}

stage('Parallel everyNode named arguments') {
    everyNode(label: 'server', parallel: true, failFast: false) {
        assertValue(env.STAGE_NAME, env.NODE_NAME, 'parallel named everyNode stage')
        exec "echo parallel-first-${env.NODE_NAME}"
        exec "echo parallel-second-${env.NODE_NAME}"
        echo "parallel-node-visited=${env.NODE_NAME}"
    }
}

stage('Parallel everyNode positional arguments') {
    everyNode('server', true, true) {
        assertValue(env.STAGE_NAME, env.NODE_NAME, 'parallel positional everyNode stage')
        echo "parallel-node-visited=${env.NODE_NAME}"
    }
}

stage('All-node everyNode') {
    everyNode {
        assertValue(env.STAGE_NAME, env.NODE_NAME, 'all-node everyNode stage')
        echo "all-node-visited=${env.NODE_NAME}"
    }
}

pipeline {
    agent {
        label 'server'
    }
    stages {
        stage('Declarative Pipeline compatibility') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    withTestDockerContainer { containerId ->
                        insideDockerContainer(containerId) {
                            exec 'echo container-exec-ok'
                        }
                    }
                }

                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    withTestDockerContainer { containerId ->
                        insideDockerContainer(containerId) {
                            assertValue(execStatus('exit 9'), 9, 'execStatus in container')
                        }
                    }
                }

                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    withTestDockerContainer { containerId ->
                        insideDockerContainer(containerId) {
                            assertValue(execStdout('echo container-stdout-ok'), 'container-stdout-ok', 'execStdout in container')
                        }
                    }
                }
            }
        }
    }
}
