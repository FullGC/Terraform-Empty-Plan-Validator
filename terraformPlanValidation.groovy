def call(jenkinsKeyIdForRepo, repoUrl, terraformVersion, terraformBucket, bootstrapSshKey, jenkinsAwsKey, awsRegion, slackChannelName) {

    step([$class: 'WsCleanup', deleteDirs: true, notFailBuild: true])

    manager.addShortText(env.environment)

    stage('Checkout') {
        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: jenkinsKeyIdForRepo, url: repoUrl]]]
    }

    def tfHome = tool name: terraformVersion, type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool'
    env.PATH = "${tfHome}:${env.PATH}"

    // Get S3 credentials
    withEnv(["AWS_DEFAULT_REGION=${awsRegion}"]) {

        wrap([$class: 'AnsiColorBuildWrapper', colorMapName: 'xterm']) {

            try {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: jenkinsAwsKey, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    withCredentials(bindings: [sshUserPrivateKey(credentialsId: "$bootstrapSshKey",
                            keyFileVariable: "bootstrapSshKey")]) {
                        stage ('Create tvars File') {

                            writeFile file: 'terraform.tfvars', text: """terraform_bucket = $terraformBucket
                            site_module_state_path = \"site/terraform.tfstate\"
                            bootstrap_ssh_key_path = \"${bootstrapSshKey}\"
                           """
                        }
                        // Mark the code build 'plan'....
                        stage ('Plan') {
                            // Output Terraform version
                            sh "terraform --version"
                            //Remove the terraform state file so we always start from a clean state
                            if (fileExists(".terraform/terraform.tfstate")) {
                                sh "rm -rf .terraform/terraform.tfstate"
                            }
                            if (fileExists("status")) {
                                sh "rm status"
                            }

                            sh "terraform get --update; terraform init"

                            def exitCode = sh script: "terraform plan -detailed-exitcode > plan.out; echo \$?", returnStatus: true

                            planFile = readFile('plan.out')
                            echo planFile
                        }
                        stage('handle result') {
                            if (exitCode == 0) {
                                currentBuild.result = 'SUCCESS'
                            } else if (exitCode == 1) {
                                currentBuild.result = 'UNSTABLE'
                                slackSend channel: slackChannelName, color: 'warning', message: "@everyone :shock: Terraform ${env.environment} plan returned an error. (<$BUILD_URL/console|Job>)"

                            } else if (exitCode == 2) {
                                currentBuild.result = 'UNSTABLE'

                                changes = sh(
                                        script: "grep module plan.out",
                                        returnStdout: true
                                ).trim().toString()

                                slackSend channel: slackChannelName, color: 'warning', message: "@everyone :facepalm-skype: There are changes to apply in Terraform ${env.environment} \". \nPlan changes are: \n $changes \n (<$BUILD_URL/console|Job>)"
                            }
                        }
                    }
                }
            } catch (any) {
                slackSend channel: slackChannelName, color: 'danger', message: "@everyone :angry-skype: Terraform ${env.environment} Validation has been broken!!\n (<$BUILD_URL/console|Job>)"
                throw any
            }
        }
    }
}