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
        stage('STAGE: Clean Up WorkSpace'){
            steps{
                deleteDir()
            }
        }

        stage('STAGE: Checkout Integration Pipeline Git Repository') {
            steps {
                git branch: 'master',
                        url: 'ssh://gerrit-gamma.gic.ericsson.se:29418/DETES/com.ericsson.de.stsoss/elementals-pipelines'
                sh '''
                    git remote set-url origin --push ssh://gerrit-gamma.gic.ericsson.se:29418/DETES/com.ericsson.de.stsoss/elementals-pipelines
                '''
            }
        }
        stage('STAGE: Load common methods') {
            steps {
                script {
                    //commonMethodsUat = load("${env.WORKSPACE}/sts_oss_ccd_pipeline/Jenkins/JobDSL/CommonMethodsUat.groovy")
                    //commonMethodsUat = load("/tmp/UAT/CommonMethodsUat.groovy")
                    commonMethodsUat = load("${env.WORKSPACE}/cENM_FlexiKube_OpenStack_UAT/CommonMethodsUat.groovy")
                }
            }
        }
        stage('STAGE: Output Provided Parameters'){
            steps {
                script {
                    commonMethodsUat.output_provided_parameters()

                    echo "Provided Build Parameters (reading from MainScript.groovy):"
                    //Checks if needed build parameters are provided
                    if (env.deployment_type == "OpenStack_IBD" || env.deployment_type == "OpenStack") {
                        if (env.DIT_deployment_name == "" || env.DTT_deployment_name == "" || env.cluster_type == ""|| env.nfs_ip == "")
                            error "Required build parameters relating to the OpenStack deployment are not populated, please check!"
                        else{
                            echo "DIT NAME: ${DIT_deployment_name}"
                            echo "DTT NAME: ${DTT_deployment_name}"
                            echo "DEPLOYMENT TYPE: ${deployment_type}"
                            echo "CLUSTER TYPE: ${cluster_type}"
                            echo "NFS VM IP: ${nfs_ip}"
                        }
                    }
                    else if (env.deployment_type == "FlexiKube") {
                        if (env.DIT_deployment_name == "" || env.HYDRA_deployment_name == "" || env.cluster_type == "")
                            error "Required build parameters relating to the FlexiKube deployment are not populated, please check!"
                        else{
                            echo "DIT NAME: ${DIT_deployment_name}"
                            echo "HYDRA NAME: ${HYDRA_deployment_name}"
                            echo "DEPLOYMENT TYPE: ${deployment_type}"
                            echo "CLUSTER TYPE: ${cluster_type}"
                        }
                    }
                    else {
                        error "The required deployment_type parameter was provided in the build parameters!"
                    }
                }
            }
        }
        stage( 'STAGE: Pre Configurations' ) {
            steps {
                script{
                    commonMethodsUat.extract_jq()
                    commonMethodsUat.readDitInformation()

                    if (env.deployment_type == "FlexiKube")
                        commonMethodsUat.readHydraInformation()

                    if (env.deployment_type == "OpenStack_IBD")
                        commonMethodsUat.readDttInformationOpenStack_IBD()
                    else if (env.deployment_type == "OpenStack")
                        commonMethodsUat.readDttInformationOpenStack()

                    if (env.deployment_type == "FlexiKube")
                        commonMethodsUat.copyKubeConfigFileFlexiKube()
                    else if (env.deployment_type == "OpenStack_IBD" || env.deployment_type == "OpenStack")
                        commonMethodsUat.copyKubeConfigFileAndPemKeyfileOpenStack()

                    commonMethodsUat.kubectlSyntax()
                }
            }
        }
        stage('STAGE: Cluster Software Version Check'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        echo "Checking Cluster Node CCD SW Version..."
                        commonMethodsUat.ccdLevelVersionCheck()

                        echo "Checking other SW details..."
                        commonMethodsUat.clusterSoftwareCheck()
                    }
                }
            }
        }
        stage('STAGE: Cluster Health Check'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.clusterNodesCheck()
                        commonMethodsUat.clusterPodsCheck()
                    }
                }
            }

        }
        stage('STAGE: RWX Storage Class Check/Configuration'){
            steps {
                script{
                    //echo "Skip"

                    if (env.deployment_type == "FlexiKube"){
                        commonMethodsUat.storageClassCheckFlexiKube()
                    }
                    else if (env.deployment_type == "OpenStack_IBD" || env.deployment_type == "OpenStack"){
                        try{
                            //sh "python cENM_FlexiKube_OpenStack_UAT/NFS_VM_configuration.py '${params.nfs_storage_class}' '${params.nfs_ip}' '${params.nfs_path_folder}'"
                            commonMethodsUat.deployNFS()
                        }
                        catch (exception){
                            echo "ERROR:\n${exception}"
                            error("An error has occurred during the NFS configuration, please check manually")
                        }
                    }
                    else{
                        echo "Skipping NFS Configuration as deployment is not OpenStack!"
                    }
                }
            }

        }
        stage('STAGE: Namespace Creation'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.createNamespaces()
                    }
                }
            }
        }
        stage('STAGE: Cluster Resource Check'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"

                        if (env.deployment_type == "FlexiKube"){
                            commonMethodsUat.readClusterDimensionsData()
                            if (commonMethodsUat.clusterAllocatedResourcesCheck() == false){
                                sh "echo 'Cluster resources do not fully match the Orderable Item / Cluster Dimensions, please check!' >> ${DIT_deployment_name}_WARNINGS.txt"
                                unstable("Cluster resources do not fully match the Orderable Item / Cluster Dimensions, please check!")
                            }
                        }
                        else if (env.deployment_type == "OpenStack"){
                            commonMethodsUat.readClusterDimensionsData()
                            commonMethodsUat.clusterAllocatedResourcesCheckOpenStack()
                        }
                        else if (env.deployment_type == "OpenStack_IBD"){
                            commonMethodsUat.readClusterDimensionsData()
                            commonMethodsUat.clusterAllocatedResourcesCheckOpenStack()
                        }
                        else{
                            sh "echo 'Cluster resources could not be checked against the O.I, invalid deployment_type provided' >> ${DIT_deployment_name}_WARNINGS.txt"
                            unstable("Cluster resources could not be checked against the O.I, invalid deployment_type provided")
                        }
                    }
                }
            }
        }
        stage('STAGE: Generating inventory file)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"

                        if (env.deployment_type == "OpenStack_IBD" || env.deployment_type == "OpenStack")
                            commonMethodsUat.createInventoryFileOpenStack()
                        else if (env.deployment_type == "FlexiKube")
                            commonMethodsUat.createInventoryFile()
                    }
                }
            }
        }
        stage('STAGE: SNMP & UI WA (TORF-485511)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        if (env.deployment_type == "FlexiKube"){
                            //TODO: IF Above CCD 2.27.0 then no need to check tunl0 as it has been removed, sysctl parameters should be already applied automatically
                            commonMethodsUat.getCCDVersion()
                            def ccdVersionCluster = "${CCD_Version}"

                            if(ccdVersionCluster.compareTo("1.27.0") >= 0) {
                                echo "CCD version of the cluster is above 1.27.0 (${ccdVersionCluster}), skipping sysctl parameter checks/updates..."
                            }
                            else if (ccdVersionCluster.compareTo("1.27.0") <= 0) {
                                echo "CCD version of the cluster is below 1.27.0 (${ccdVersionCluster}), sysctl parameters are to be applied..."
                                commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.conf.all.rp_filter","0")
                                commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.conf.tunl0.rp_filter","0")
                                commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.vs.conntrack","1")
                            }
                            else {
                                unstable("Could not find cluster CCD version, skipping sysctl parameter update, please check!")
                            }
                        }
                        else if (env.deployment_type == "OpenStack_IBD"){
                            commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.conf.all.rp_filter","0")
                            commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.conf.tunl0.rp_filter","0")
                            commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.vs.conntrack","1")
                        }
                        else if (env.deployment_type == "OpenStack"){
                            commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.conf.all.rp_filter","0")
                            commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.conf.eth0.rp_filter","0")
                            commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.vs.conntrack","1")
                        }
                        else{
                            unstable("Could not find deployment type, skipping update, please check!")
                        }
                    }
                }
            }
        }
        stage('STAGE: net.ipv4.vs.conn_reuse_mode and net.ipv4.vs.expire_nodest_conn parameter configuration (TORF-598973)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.vs.conn_reuse_mode","0")
                        commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.vs.expire_nodest_conn","1")
                    }
                }
            }
        }
        stage('STAGE: net.ipv4.vs.run_estimation parameter configuration (TORF-619252)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.systemctlParameterUpdateForWorkerNodes("net.ipv4.vs.run_estimation","1")
                    }
                }
            }
        }
        stage('STAGE: vm.max_map_count parameter configuration'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.systemctlParameterUpdateForWorkerNodes("vm.max_map_count","262144")
                    }
                }
            }
        }
        stage('STAGE: cENM Load Balancer IP Check'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.enmLoadBalancerCheck()
                    }
                }
            }
        }
        stage('STAGE: nslookup of CCD API URL)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.checkAPInslookup()
                    }
                }
            }
        }
        stage('STAGE: CCD LoadBalancer Service Check)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.CCD_loadBalancerServiceCheck()
                    }
                }
            }
        }
        stage('STAGE: Read excludeCIDRs)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.readExcludeCIDRs()
                    }
                }
            }
        }
        stage('STAGE: Read ECFE)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        if (env.deployment_type == "OpenStack"){
                            commonMethodsUat.readECFE_OpenStack()
                        }
                        else {
                            commonMethodsUat.readECFE()
                        }
                    }
                }
            }
        }
        stage('STAGE: READ Node Internal IPs)'){
            steps {
                catchError(stageResult: 'FAILURE') {
                    script{
                        //echo "Skip"
                        commonMethodsUat.readNodeInternalIPs()
                    }
                }
            }
        }
        stage('STAGE: DIT kubeconfig file update'){
            steps {
                withCredentials([usernamePassword(credentialsId: 'detsFunUser', passwordVariable: 'detspassword', usernameVariable: 'detsusername')]) {

                    catchError(stageResult: 'FAILURE') {
                        script{
                            //echo "Skip"

                            updateKubeconfigDIT = commonMethodsUat.updateKubeconfigDIT()

                            if (updateKubeconfigDIT == true){
                                echo "Updating kubeconfig file in DIT..."
                                sh "curl -u '${detsusername}:${detspassword}' -X PUT 'https://atvdit.athtem.eei.ericsson.se/api/documents/${kubeConfigID}' -H 'accept: application/json' -H 'Content-Type: application/json' -d @kubeconfig/json_get_config"
                            }
                            else{
                                sh "echo 'Something went wrong updating DIT, please check! Manual update may be required.' >> ${DIT_deployment_name}_WARNINGS.txt"
                                unstable("Something went wrong updating DIT, please check! Manual update may be required.")
                            }
                        }
                    }
                }
            }
        }
        stage('STAGE: DIT taf_properties document update'){
            steps {
                withCredentials([usernamePassword(credentialsId: 'detsFunUser', passwordVariable: 'detspassword', usernameVariable: 'detsusername')]) {

                    catchError(stageResult: 'FAILURE') {
                        script{
                            //echo "Skip"
                            updateTafProperties = commonMethodsUat.updateTafProperties()

                            if (updateTafProperties == true)
                                sh "curl -u '${detsusername}:${detspassword}' -X PUT 'https://atvdit.athtem.eei.ericsson.se/api/documents/${tafPropertiesID}' -H 'accept: application/json' -H 'Content-Type: application/json' -d @tafProperties/json_get_properties"
                            else if (updateTafProperties == false)
                                echo "DIT taf_properties updates are not needed"
                            else{
                                sh "echo 'DIT taf_properties document is not correctly configured, please check!' >> ${DIT_deployment_name}_WARNINGS.txt"
                                unstable("DIT taf_properties document is not correctly configured, please check!")
                            }
                        }
                    }
                }
            }
        }
        stage('STAGE: DIT site_information document update'){
            steps {
                withCredentials([usernamePassword(credentialsId: 'detsFunUser', passwordVariable: 'detspassword', usernameVariable: 'detsusername')]) {

                    catchError(stageResult: 'FAILURE') {
                        script{
                            echo "Skipping stage (as part of new cENM version control this document update is no longer needed)"
                            
                            /*
                            updateSiteInformationDocument = commonMethodsUat.updateSiteInformationDocument()
                            if (updateSiteInformationDocument == true)
                                sh "curl -u '${detsusername}:${detspassword}' -X PUT 'https://atvdit.athtem.eei.ericsson.se/api/documents/${site_informationID}' -H 'accept: application/json' -H 'Content-Type: application/json' -d @siteInformation/json_get_properties"
                            else if (updateSiteInformationDocument == false)
                                echo "DIT site_information updates are not needed"
                            else{
                                sh "echo 'DIT site_information document is not correctly configured, please check!' >> ${DIT_deployment_name}_WARNINGS.txt"
                                unstable("DIT site_information document is not correctly configured, please check!")
                            }
                             */
                        }
                    }
                }
            }
        }
        stage('STAGE: DIT .pem file update)'){
            steps {
                withCredentials([usernamePassword(credentialsId: 'detsFunUser', passwordVariable: 'detspassword', usernameVariable: 'detsusername')]) {

                    catchError(stageResult: 'FAILURE') {
                        script{
                            echo "Skip"

                            if (env.deployment_type == "OpenStack_IBD" || env.deployment_type == "OpenStack"){
                                commonMethodsUat.uploadPemFileDit_OpenStack()
                                sh "curl -u '${detsusername}:${detspassword}' -X PUT 'https://atvdit.athtem.eei.ericsson.se/api/deployments/${myHead}' -H 'accept: application/json' -H 'Content-Type: application/json' -d @pemconfig/json_put_pem"
                            }
                            else if (env.deployment_type == "FlexiKube"){
                                commonMethodsUat.uploadPemFileDit_FlexiKube()
                                sh "curl -u '${detsusername}:${detspassword}' -X PUT 'https://atvdit.athtem.eei.ericsson.se/api/deployments/${myHead}' -H 'accept: application/json' -H 'Content-Type: application/json' -d @pemconfig/json_put_pem"
                            }
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
                currentBuild.displayName = "#${BUILD_NUMBER}: ${DIT_deployment_name}-UAT"
            }
            archiveArtifacts artifacts: " ${DIT_deployment_name}_WARNINGS.txt", allowEmptyArchive: true
            archiveArtifacts artifacts: " ${DIT_deployment_name}_JIRA.txt", allowEmptyArchive: true
            archiveArtifacts artifacts: " nfs_configuration.txt", allowEmptyArchive: true
            archiveArtifacts artifacts: " old_chrony.conf", allowEmptyArchive: true
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