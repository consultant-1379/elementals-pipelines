//def commonMethods
def commonMethodsUat

pipeline{
    agent {
        node
                {
                    label NODE_LABEL
                }
    }
    environment {
        HOME_DIR = "${WORKSPACE}"
        cenm_utilities_docker_image = "armdocker.rnd.ericsson.se/proj-enm/cenm-build-utilities:latest"
        nexus_repositoryUrl = "https://arm902-eiffel004.athtem.eei.ericsson.se:8443/nexus/content/repositories/releases/"
        helm_repository_release = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-helm/"
        helm_repository_ci_internal = "https://arm.seli.gic.ericsson.se/artifactory/proj-enm-dev-internal-helm/"
        csar_package_name = "enm-installation-package"
        Client_HOME = "/home/cenmbuild"
    }
    stages{
        stage('Clean Up WorkSpace'){
            steps{
                deleteDir()
            }
        }

        stage('Checkout Integration Pipeline Git Repository') {
            steps {
                git branch: 'master',
                        url: 'ssh://gerrit.ericsson.se:29418/DETES/com.ericsson.de.stsoss/elementals-pipelines'
                sh '''
                    git remote set-url origin --push ssh://gerrit.ericsson.se:29418/DETES/com.ericsson.de.stsoss/elementals-pipelines
                '''
            }
        }
        stage('Load common methods') {
            steps {
                script {
                    //commonMethodsUat = load("${env.WORKSPACE}/sts_oss_ccd_pipeline/Jenkins/JobDSL/CommonMethodsUat.groovy")
                    //commonMethodsUat = load("/tmp/UAT/CommonMethodsUat.groovy")
                    commonMethodsUat = load("${env.WORKSPACE}/FlexiKube_UAT_Pipeline/CommonMethodsUat.groovy")
                }
            }
        }
        stage( 'Pre Configurations' ) {
            steps {
                script{
                    commonMethodsUat.extract_jq()
                    /*CommonMethodsUat.download_kube_config_file_from_dit()
                    CommonMethodsUat.read_site_config_info_from_dit()
                    CommonMethodsUat.set_kube_config_file()*/
                    commonMethodsUat.read_inventory_file()
                    commonMethodsUat.readHydraInformation()
                    commonMethodsUat.copyKubeConfigFile()
                    commonMethodsUat.kubectlSyntax()
                    commonMethodsUat.readDitInformation()
                }
            }
        }
        stage('Cluster Software Version Check'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.ccdLevelVersionCheck()
                        commonMethodsUat.clusterSoftwareCheck()
                    }
                }
            }
        }
        stage('Cluster Health Check'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"

                        if (commonMethodsUat.clusterNodesCheck() == false)
                            unstable("One or more nodes are in NotReady status, please check!")

                        if (commonMethodsUat.clusterPodsCheck() == false)
                            unstable("Cluster has Pods not in fully Ready status, please check!")

                        if (commonMethodsUat.storageClassCheck() == false)
                            unstable("csi-cephfs-sc storage class is not present on the cluster, please check!")
                    }
                }
            }

        }
        stage('Namespace Creation'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.createNamespaces()
                    }
                }
            }
        }
        stage('Cluster Resource Check'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.readClusterDimensionsData()

                        if (commonMethodsUat.clusterAllocatedResourcesCheck() == false)
                            unstable("Cluster resources do not fully match the Orderable Item / Cluster Dimensions, please check!")
                    }
                }
            }
        }
        stage('SNMP & UI WA (TORF-485511)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.createInventoryFile()
                        commonMethodsUat.SNMP_and_UI_WA()
                    }
                }
            }
        }
        stage('net.ipv4.vs.conn_reuse_mode and net.ipv4.vs.expire_nodest_conn parameter configuration (TORF-598973)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.sysctlParameterUpdate2()
                    }
                }
            }
        }
        stage('net.ipv4.vs.run_estimation parameter configuration (TORF-619252)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.sysctlParameterUpdate3()
                    }
                }
            }
        }
        stage('Client Machine VM Configuration (Post initial configuration)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        echo "Skip"
                        //commonMethodsUat.clientMachineConfiguration()
                    }
                }
            }
        }
        stage('enmHOST URL DNS Check'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"

                        if (commonMethodsUat.enmHostURLCheck() == false)
                            unstable("DNS entry is not configured as per DIT LB IP, please check!")
                    }
                }
            }
        }
        stage('DualStack Configuration Check)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.dualStackConfigurationCheck()
                    }
                }
            }
        }
        stage('READ ECFE)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.readECFE()
                    }
                }
            }
        }
        stage('DIT update'){
            steps {
                withCredentials([usernamePassword(credentialsId: 'detsFunUser', passwordVariable: 'detspassword', usernameVariable: 'detsusername')]) {

                    catchError(stageResult: 'FAILURE') {
                        script{
                            //echo "Skip"
                            commonMethodsUat.updateDIT()
                            sh "curl -u '${detsusername}:${detspassword}' -X PUT 'https://atvdit.athtem.eei.ericsson.se/api/documents/${kubeConfigID}' -H 'accept: application/json' -H 'Content-Type: application/json' -d @kubeconfig/json_get_config"
                        }
                    }
                }
            }
        }
        stage('Upload Pem File)'){
            steps {
                withCredentials([usernamePassword(credentialsId: 'detsFunUser', passwordVariable: 'detspassword', usernameVariable: 'detsusername')]) {

                    catchError(stageResult: 'FAILURE') {
                        script{
                            //echo "Skip"
                            commonMethodsUat.uploadPemFileDit()
                            sh "curl -u '${detsusername}:${detspassword}' -X PUT 'https://atvdit.athtem.eei.ericsson.se/api/deployments/${myHead}' -H 'accept: application/json' -H 'Content-Type: application/json' -d @pemconfig/json_put_pem"
                        }
                    }
                }
            }
        }
    }

    post{
        failure {
            script{
                echo "Failure"
            }
        }
        aborted{
            script{
                echo "Aborted"
            }
        }
        success{
            script{
                echo "Success"
            }
        }
        always {
            script{
                currentBuild.displayName = "#${BUILD_NUMBER}: ${environment_name}-UAT"
            }
            archiveArtifacts artifacts: " ${environment_name}_JIRA.txt", allowEmptyArchive: true
            archiveArtifacts artifacts: " ${environment_name}_NotRunningPods.txt", allowEmptyArchive: true
        }
    }
}

/*
def clone_ci_repo(){
   sh '''
       [ -d eric-enm-integration-pipeline-code ] && rm -rf eric-enm-integration-pipeline-code
        git clone ${GERRIT_MIRROR}/OSS/com.ericsson.oss.containerisation/eric-enm-integration-pipeline-code
   '''
}


def pullPatchset(){
    if (env.GERRIT_REFSPEC !='' && env.GERRIT_REFSPEC != "refs/heads/master") {
        sh '''
        pwd
        cd dets_automation_pipeline
        pwd
        git fetch ssh://gerrit.ericsson.se:29418/DETES/com.ericsson.de.stsoss/dets_automation_pipeline $GERRIT_REFSPEC && git checkout FETCH_HEAD
        '''
    }
}*/