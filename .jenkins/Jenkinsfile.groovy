def assertValue(actual, expected, description) {
    if (actual != expected) {
        error "${description}: expected '${expected}', got '${actual}'"
    }
}

node('server') {
    stage('Host command steps') {
        assertValue(isWindows(), !isUnix(), 'isWindows on host')
        exec 'echo host-exec-ok'
        assertValue(execStatus('exit 7'), 7, 'execStatus on host')
        assertValue(execStdout('printf host-stdout-ok'), 'host-stdout-ok', 'execStdout on host')
    }

    stage('Command bookkeeping') {
        dir('bookkeeping-repository') {
            withEnv(["WORKSPACE_TMP=${pwd()}/missing-temp"]) {
                def files = execStdout "find . -maxdepth 1 -name '.pipeline-*' -print"
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
            docker.image('mcr.microsoft.com/windows/nanoserver:1809').inside {
                exec 'echo container-exec-ok'
            }
        }
    }

    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        stage('docker.image.inside execStatus') {
            docker.image('mcr.microsoft.com/windows/nanoserver:1809').inside {
                assertValue(execStatus('exit 9'), 9, 'execStatus in container')
            }
        }
    }

    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        stage('docker.image.inside  execStdout') {
            docker.image('mcr.microsoft.com/windows/nanoserver:1809').inside {
                assertValue(execStdout('printf container-stdout-ok'), 'container-stdout-ok', 'execStdout in container')
                def files = execStdout "find . -maxdepth 1 -name '.pipeline-*' -print"
                assertValue(files, '', 'container command bookkeeping outside current directory')
            }
        }
    }

    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        stage('insideDockerContainer exec') {
            insideDockerContainer('agents_compose-unity') {
                exec 'echo container-exec-ok'
            }
        }
    }

    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        stage('insideDockerContainer execStatus') {
            insideDockerContainer('agents_compose-unity') {
                assertValue(execStatus('exit 9'), 9, 'execStatus in container')
            }
        }
    }

    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        stage('insideDockerContainer execStdout') {
            insideDockerContainer('agents_compose-unity') {
                assertValue(execStdout('printf container-stdout-ok'), 'container-stdout-ok', 'execStdout in container')
                def files = execStdout "find . -maxdepth 1 -name '.pipeline-*' -print"
                assertValue(files, '', 'container command bookkeeping outside current directory')
            }
        }
    }

    stage('Current-node everyNode') {
        def currentNode = env.NODE_NAME
        everyNode(env.NODE_NAME) {
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
    everyNode(label: 'server', parallel: true) {
        assertValue(env.STAGE_NAME, env.NODE_NAME, 'parallel named everyNode stage')
        exec "echo parallel-first-${env.NODE_NAME}"
        exec "echo parallel-second-${env.NODE_NAME}"
        echo "parallel-node-visited=${env.NODE_NAME}"
    }
}

stage('Parallel everyNode positional arguments') {
    everyNode('server', true) {
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
    agent  {
        label 'server'
    }
    stages {
        stage('Pipeline Tests') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    insideDockerContainer('agents_compose-unity') {
                        exec 'echo container-exec-ok'
                    }
                }

                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    insideDockerContainer('agents_compose-unity') {
                        assertValue(execStatus('exit 9'), 9, 'execStatus in container')
                    }
                }

                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    insideDockerContainer('agents_compose-unity') {
                        assertValue(execStdout('printf container-stdout-ok'), 'container-stdout-ok', 'execStdout in container')
                    }
                }
            }
        }
    }
}