pipeline {
    //spring server eks에 배포하는 예시
    agent any
    environment {
        DOCKER_IMAGE_VERSION = ""
        DOCKER_IMAGE_NAME = ""
        ECR_REGISTRY_URL = ""
        ECR_TOKEN = ""
        OLD_DEPLOYMENT_NAME = ""
        NEW_DEPLOYMENT_NAME = ""
        DEPLOYMENT_PREFIX = ""
        SERVICE_NAME = ""
        EKS_SPACE_NAME = ""
        EKS_CLUSTER_CONTEXT_NAME = ""
    }
    stages {
        stage('GitClone') {
            steps {
                git branch: 'develop', credentialsId: ''
            }
        }
        stage('GetSecretManagerAndCreateConfigFile') {
            steps {
                withEnv(['AWS_DEFAULT_PROFILE=scretmanager']) {
                    sh '''mkdir -p aws-yml'''
                    sh '''mkdir -p local-yml'''
                    sh '''aws secretsmanager get-secret-value --secret-id [] --query 'SecretString'  --region ap-northeast-2 --output text > aws-yml/application.yml'''
                }
            }
        }
        stage('SetProjectFiles') {
            steps {
                sh '''mv src/main/resources/application.yml local-yml/application_bak.yml'''
                sh '''cp aws-yml/application.yml src/main/resources'''
            }
        }
        stage('BuildJarAndTest') {
            steps {
                sh "./gradlew clean build -x test"
            }
        }
        // stage('Publish Jacoco Report') {
        //     steps {
        //         publishHTML(target: [
        //             allowMissing: false,
        //             alwaysLinkToLastBuild: true,
        //             keepAll: true,
        //             reportDir: 'build/reports/tests/test',
        //             reportFiles: 'index.html',
        //             reportName: 'Jacoco Test Coverage'
        //         ])
        //     }
        // }
        stage('GetEcrTokenAndDockerLogin') {
            steps {
                withEnv(['AWS_DEFAULT_PROFILE=xyzecr']) {
                    script {
                        ECR_TOKEN = sh(script: "aws ecr get-login-password --region ap-northeast-2", returnStdout: true).trim()
                        sh "sudo docker login -u AWS -p ${ECR_TOKEN} ${ECR_REGISTRY_URL}"
                    }
                }
            }
        }
        stage('RunDockerFileAndPushImage') {
            steps {
                script {
                    def jenkinsBuildNumber = env.BUILD_NUMBER
                    DOCKER_IMAGE_VERSION = "1.0.${jenkinsBuildNumber}"
                    sh "sudo docker buildx use multi-platform-builder"
                    sh "sudo docker buildx inspect --bootstrap"
                    sh "sudo docker buildx build --platform linux/arm64,linux/amd64 -t ${ECR_REGISTRY_URL}/${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_VERSION} -t ${ECR_REGISTRY_URL}/${DOCKER_IMAGE_NAME}:latest --push ."
                }
            }
        }
        stage('RemoveSecretManagerData') {
            steps {
                sh '''rm  src/main/resources/application.yml'''
                sh '''mv local-yml/application_bak.yml src/main/resources/application.yml'''
            }
        }
        stage('Set Kubectl Context') {
            steps {
                withEnv(['AWS_DEFAULT_PROFILE=eksadmin']) {
                    script {
                        sh 'sudo kubectl config use-context ${EKS_CLUSTER_CONTEXT_NAME}'
                    }
                }
            }
        }
        stage('Get Old Deployment Info') {
            steps {
                withEnv(['AWS_DEFAULT_PROFILE=eksadmin']) {
                    script {
                        try {
                            OLD_DEPLOYMENT_NAME = sh(script: "kubectl get deployment -n ${EKS_SPACE_NAME} -o=jsonpath='{.items[0].metadata.name}'", returnStdout: true).trim()
                            if (OLD_DEPLOYMENT_NAME == "") {
                                throw new Exception("No old deployment found")
                            }
                        } catch (Exception e) {
                            echo "No old deployment found. Assuming initial deployment."
                            OLD_DEPLOYMENT_NAME = null
                        }
                        echo "Deployment Name: ${OLD_DEPLOYMENT_NAME}"
                    }
                }
            }
        }
        stage('Prepare Deployment File') {
            steps {
                script {
                    def jenkinsBuildNumber = env.BUILD_NUMBER
                    NEW_DEPLOYMENT_NAME = "${DEPLOYMENT_PREFIX}${jenkinsBuildNumber}"

                    sh "yq -i -y '.metadata.name = \"${NEW_DEPLOYMENT_NAME}\"' eksDeployment.yaml"
                    sh "yq -i -y '.spec.template.metadata.labels.version = \"${jenkinsBuildNumber}\"' eksDeployment.yaml"
                    sh "yq -i -y '.spec.selector.matchLabels.version = \"${jenkinsBuildNumber}\"' eksDeployment.yaml"
                    sh "yq -i -y '.spec.template.spec.containers[0].image = \"${ECR_REGISTRY_URL}/${DOCKER_IMAGE_NAME}:${DOCKER_IMAGE_VERSION}\"' eksDeployment.yaml"


                    sh "cat eksDeployment.yaml"
                }
            }
        }
        stage('Deploy to EKS') {
            steps {
                withEnv(['AWS_DEFAULT_PROFILE=eksadmin']) {
                    script {
                        def jenkinsBuildNumber = env.BUILD_NUMBER
                        // 배포
                        sh 'kubectl apply -f eksDeployment.yaml'

                        // kubectl rollout 상태 확인
                        try {
                            //rollout status 로 완료까지 감시함(타임아웃은 일단 임의로.)
                            sh "kubectl rollout status deployment/${NEW_DEPLOYMENT_NAME} -n ${EKS_SPACE_NAME} --timeout=600s"
                            echo 'Deployment Successful'

                            //서비스가 가리키는 대상 업데이트
                            sh "kubectl patch service ${SERVICE_NAME} -n ${EKS_SPACE_NAME} --type='json' -p='[{\"op\": \"replace\", \"path\": \"/spec/selector/version\", \"value\":\"${jenkinsBuildNumber}\"}]'"
                            echo 'Service target updated'

                            // 구형 디플로이먼트 제거함 - OLD_DEPLOYMENT_NAME이 null이 아닌 경우에만 실행
                            if (OLD_DEPLOYMENT_NAME != null) {
                                sh "kubectl delete deployment ${OLD_DEPLOYMENT_NAME} -n ${EKS_SPACE_NAME}"
                                echo 'Old deployment removed'
                            }

                        } catch (Exception e) {
                            echo 'Deployment Failed'
                            throw e
                        }
                    }
                }
            }
        }
    }
    options {
        timeout(time: 12, unit: 'HOURS') // 전체 파이프라인 실행 타임아웃을 12시간으로 설정
    }
}
