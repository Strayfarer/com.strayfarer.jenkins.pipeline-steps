
def assertValue(actual, expected, description) {
    if (actual != expected) {
        error "${description}: expected '${expected}', got '${actual}'"
    }
}

node('Mörkö') {
    stage('Host command steps') {
        exec 'echo host-exec-ok'
        assertValue(execStatus('exit 7'), 7, 'execStatus on host')
        assertValue(execStdout('printf host-stdout-ok'), 'host-stdout-ok', 'execStdout on host')
    }

    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        stage('Container exec') {
            insideDockerContainer('agents_compose-unity') {
                exec 'echo container-exec-ok'
            }
        }
    }

    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        stage('Container execStatus') {
            insideDockerContainer('agents_compose-unity') {
                assertValue(execStatus('exit 9'), 9, 'execStatus in container')
            }
        }
    }

    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
        stage('Container execStdout') {
            insideDockerContainer('agents_compose-unity') {
                assertValue(execStdout('printf container-stdout-ok'), 'container-stdout-ok', 'execStdout in container')
            }
        }
    }

    stage('Current-node everyNode') {
        def currentNode = env.NODE_NAME
        everyNode('Mörkö') {
            assertValue(env.NODE_NAME, currentNode, 'current-node everyNode')
            echo "current-node-visited=${env.NODE_NAME}"
        }
    }
}

stage('Queued everyNode') {
    everyNode('Mörkö') {
        echo "queued-node-visited=${env.NODE_NAME}"
    }
}

stage('Parallel everyNode') {
    everyNode(label: 'Mörkö', parallel: true) {
        echo "parallel-node-visited=${env.NODE_NAME}"
    }
}

stage('All-node everyNode') {
    everyNode {
        echo "all-node-visited=${env.NODE_NAME}"
    }
}
