import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.yaml.*
import java.util.regex.Pattern
import java.net.InetAddress

def extract_jq(){
    echo "Extracting the jq software"
    sh "tar -xvf software/jq-1.0.1.tar ; chmod +x ./jq"
}

def output_provided_parameters() {
    echo "\nProvided Build Parameters (reading from commonMethodsUAT.groovy):"
    echo "DIT NAME: ${DIT_deployment_name}"
    echo "HYDRA NAME: ${HYDRA_deployment_name}"
    echo "DTT NAME: ${DTT_deployment_name}"
    echo "DEPLOYMENT TYPE: ${deployment_type}"
    echo "CLUSTER TYPE: ${cluster_type}"
    echo "NFS VM IP: ${nfs_ip}"
}

def printToElementalsReport(name,output){
    sh "echo \"\n${name}\n\n{noformat}\n${output}\n{noformat}\n\" >> ${DIT_deployment_name}_JIRA.txt"
}

def readHydraInformation() {
    echo "Pre Configurations: Reading values present in HYDRA"
    productionToken = "d258d9d946c771448b8de590f735b90e309b56a9"
    hydra = "curl -k -s -X GET -H 'Authorization: ${productionToken}'"
    custom_field_id_directorCPU = "14506"
    getDirectorCPU = "https://hydra.gic.ericsson.se/api/8.0/instance_custom_data?instance_id=418433\\&custom_field_id=14506"


    // Get Instance ID from cluster name
    instanceID = sh (script : "${hydra} https://hydra.gic.ericsson.se/api/8.0/instance?name=${HYDRA_deployment_name} | ./jq '.result[0].id'",returnStdout: true).trim()
    //echo "Instance ID  = ${instanceID}"

    directorCPU = sh (script : "curl -k -s -X GET -H 'Authorization: ${productionToken}' ${getDirectorCPU} | ./jq '.result[0].data'",returnStdout: true).trim()
    //echo "Director CPU Total = ${directorCPU}"

    def CustomFieldIds = ["OwnerSignum" : ["12978",""],
                          //"NumberOfWorkers" : ["12984",""],
                          //"WorkerCPU" : ["12985",""],
                          //"WorkerMemory" : ["12986",""],
                          //"WorkerDiskSize" : ["12987",""],
                          //"WorkersNodes" : ["14273",""],
                          //"DirectorCPU" : ["14506",""],
                          //"DirectorMemory" : ["14507",""],
                          //"DirectorDiskSize" : ["14508",""],
                          //"MasterCPU" : ["18693",""],
                          //"MasterMemory" : ["18694",""],
                          //"MasterDiscSize" : ["18695",""],
                          "DirectorNodes" : ["14271",""]]
    for (key in CustomFieldIds.keySet()){
        //CustomFieldIds[key][1] = sh (script : "curl -k -s -X GET -H 'Authorization: ${productionToken}' ${CustomFieldIds[key][0]} | ./jq '.result[0].data'",returnStdout: true).trim()
        CustomFieldIds[key][1] = sh (script : "curl -k -s -X GET -H 'Authorization: ${productionToken}' https://hydra.gic.ericsson.se/api/8.0/instance_custom_data?instance_id=${instanceID}\\&custom_field_id=${CustomFieldIds[key][0]} | ./jq '.result[0].data'",returnStdout: true).trim()
        echo "${key} = ${CustomFieldIds[key][1]}"
    }

    def directorNodeIP = readJSON text: CustomFieldIds["DirectorNodes"][1].substring(1, CustomFieldIds["DirectorNodes"][1].size() - 1)
    echo "Director Node IP (as per HYDRA) is ${directorNodeIP[0].dir_oam_ip}"

    //Use this for the cluster director node commands
    env.directorNodeSSH = "sshpass -p ${CustomFieldIds['OwnerSignum'][1]} ssh -o LogLevel=error -o 'StrictHostKeyChecking=no' ${CustomFieldIds['OwnerSignum'][1]}@${directorNodeIP[0].dir_oam_ip}"

    //Used for copying the kubeconfig file from the director node of the cluster to the workspace folder of the Jenkins slave
    env.clusterOwnerSignum = "${CustomFieldIds['OwnerSignum'][1]}"
    env.directorNodeIP = "${directorNodeIP[0].dir_oam_ip}"
}

def readDttInformationOpenStack() {
    echo "Pre Configurations: Reading values present in DTT (OpenStack_CAPO)"
    dttDeploymentUrlName = "https://atvdtt.athtem.eei.ericsson.se/api/deployments?q=name%3D${DTT_deployment_name}"
    dttDocumentHead = 'https://atvdtt.athtem.eei.ericsson.se/api/documents/'

    //extract the enm control plane IP from the controlplanevip.yml document
    try{
        //def control_plane_vip_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-c16a028/ccd-c16a028.controlplanevip.yml",returnStdout: true).trim()
        //def control_plane_pem_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-c16a028/ccd-c16a028.controlplane.pem",returnStdout: true).trim()
        //def kubeconfig_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-c16a028/ccd-c16a028.target_kubeconfig.conf",returnStdout: true).trim()

        def control_plane_vip_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-${DTT_deployment_name}/ccd-${DTT_deployment_name}.controlplanevip.yml",returnStdout: true).trim()
        def control_plane_pem_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-${DTT_deployment_name}/ccd-${DTT_deployment_name}.controlplane.pem",returnStdout: true).trim()
        def kubeconfig_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-${DTT_deployment_name}/ccd-${DTT_deployment_name}.target_kubeconfig.conf",returnStdout: true).trim()

        //def control_plane_vip_document_JSON = new JsonSlurper().parseText(control_plane_vip_document)
        //nv.control_plane_vip = control_plane_vip_document_JSON.controlplanevip
        //
        echo "The control_plane_vip is:\n${control_plane_vip_document}"
        def split_control_plane_vip_document = control_plane_vip_document.split("\\s+")
        echo "The DTT control_plane_vip is:\n"+ split_control_plane_vip_document[-1]
        env.controlPlaneIP = split_control_plane_vip_document[-1]
        env.directorNodeIP = split_control_plane_vip_document[-1]

        env.pemFile = control_plane_pem_document
        echo "The DTT control_plane_pem_document is:\n${control_plane_pem_document}"

        //def control_plane_pem_document_split = pemFile.split('\n') //Adds escape characters to ends of each line
        //env.pemFile = env.pemFile.collect {it + 'r\n'} //Adds escape characters to ends of each line
        //def parsePemFile = removeEscape(env.pemFile)
        //echo "The converted pem keyfile contents:\n"+ parsePemFile

        env.kube_config_DTT_Contents = kubeconfig_document
        echo "The DTT kubeconfig_document is:\n${kubeconfig_document}"
    }
    catch (exception){
        echo "ERROR:\n${exception}"
    }

}
def readDttInformationOpenStack_IBD() {
    echo "Pre Configurations: Reading values present in DTT (OpenStack)"
    dttDeploymentUrlName = "https://atvdtt.athtem.eei.ericsson.se/api/deployments?q=name%3D${DTT_deployment_name}"
    dttDocumentHead = 'https://atvdtt.athtem.eei.ericsson.se/api/documents/'

    //extract the enm control plane IP from the controlplanevip.yml document
    try{
        //CAPO
        //def control_plane_vip_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-c16a028/ccd-c16a028.controlplanevip.yml",returnStdout: true).trim()
        //def control_plane_pem_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-c16a028/ccd-c16a028.controlplane.pem",returnStdout: true).trim()
        //def kubeconfig_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-c16a028/ccd-c16a028.target_kubeconfig.conf",returnStdout: true).trim()

        def control_plane_vip_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-${DTT_deployment_name}/ccd-${DTT_deployment_name}.directorvip.yml",returnStdout: true).trim()
        def control_plane_pem_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-${DTT_deployment_name}/ccd-${DTT_deployment_name}.director.pem",returnStdout: true).trim()
        def kubeconfig_document = sh (script : "curl -X GET http://object.athtem.eei.ericsson.se/de-cni/ccd/ccd-${DTT_deployment_name}/ccd-${DTT_deployment_name}.admin.conf",returnStdout: true).trim()

        //def control_plane_vip_document_JSON = new JsonSlurper().parseText(control_plane_vip_document)
        //nv.control_plane_vip = control_plane_vip_document_JSON.controlplanevip
        //
        echo "DTT director_vip document contents:\n${control_plane_vip_document}"
        def split_control_plane_vip_document = control_plane_vip_document.split("\\s+")
        echo "Director node IP (as per DTT):\n"+ split_control_plane_vip_document[-1]
        env.directorNodeIP = split_control_plane_vip_document[-1]

        env.pemFile = control_plane_pem_document
        echo "DTT director node .pem keyfile contents:\n${control_plane_pem_document}"


        //def control_plane_pem_document_split = pemFile.split('\n') //Adds escape characters to ends of each line
        //env.pemFile = env.pemFile.collect {it + 'r\n'} //Adds escape characters to ends of each line
        //def parsePemFile = removeEscape(env.pemFile)
        //echo "The converted pem keyfile contents:\n"+ parsePemFile

        env.kube_config_DTT_Contents = kubeconfig_document
        echo "The DTT cluster kubeconfig document contents:\n${kubeconfig_document}"
    }
    catch (exception){
        echo "ERROR:\n${exception}"
    }

}

def kubectlSyntax() {
    env.kubeConfig = "${workspace}/kubeconfig/config"
    env.kubectl = "docker run --rm  -v ${kubeConfig}:/root/.kube/config -v ${WORKSPACE}:${WORKSPACE} --workdir ${WORKSPACE} ${cenm_utilities_docker_image} kubectl"
    env.helm = "docker run --rm -v ${kubeConfig}:/root/.kube/config -v ${WORKSPACE}:${WORKSPACE} --workdir ${WORKSPACE} ${cenm_utilities_docker_image} helm"
}

def copyKubeConfigFileFlexiKube() {
    sh "mkdir -p ${WORKSPACE}/kubeconfig/"

    //SCP the Kubeconfig file from the director node of the cluster onto the workspace directory of the Jenkins slave using directorNodeIP and clusterOnwerSignum obtained from HYDRA
    sh "sshpass -p ${clusterOwnerSignum} scp -o 'StrictHostKeyChecking=no' ${clusterOwnerSignum}@${directorNodeIP}:/home/${clusterOwnerSignum}/.kube/config ${WORKSPACE}/kubeconfig/config"
}

def copyKubeConfigFileAndPemKeyfileOpenStack() {
    echo "STAGE: Pre Configurations: Copying over the kubeconfig file of the k8s cluster and the director/control-plane node .pem keyfile of k8s cluster onto the Jenkins slave"

    //copy over the director node .pem keyfile contents onto the workspace directory
    echo "Creating /pem folder in the Jenkins slave workspace directory..."
    sh "mkdir -p ${WORKSPACE}/pem/"

    echo "Outputting/uploading .pem contents inside /pem/director.pem file inside the Jenkins Slave workspace directory"
    sh "echo '${pemFile}' >> ${WORKSPACE}/pem/director.pem"

    echo "Changing permissions of /pem/director.pem on the Jenkins slave..."
    sh "chmod 600 ${WORKSPACE}/pem/director.pem"

    echo "Creating /kubeconfig folder in the Jenkins workspace directory"
    sh "mkdir -p ${WORKSPACE}/kubeconfig/"

    //copy over the kubeconfig file retrieved from DTT onto the workspace directory
    sh "echo '${kube_config_DTT_Contents}' >> ${WORKSPACE}/kubeconfig/config "

    //output the kubeconfig and .pem keyfile contents present on the Jenkins Slave workspace folder
    echo "kubeconfig and .pem keyfile contents saved in the Jenkins slaves workspace folder:"
    sh "cat ${WORKSPACE}/kubeconfig/config"
    sh "cat ${WORKSPACE}/pem/director.pem"

    //Use this for the cluster director node commands
    env.directorNodeSSH = "sshpass -p eccd ssh -o LogLevel=error -o 'StrictHostKeyChecking=no' eccd@${directorNodeIP} -i ${WORKSPACE}/pem/director.pem"
}

//helper function for method readDitInformation()
def ditKeyCheck(keyValue, keyName, documentName){
    echo "The KEY [${keyName}] is set to [${keyValue}] inside the DIT ${documentName} document!"

    if ("${keyValue}" == "null"){
        //sh "echo 'The KEY ${keyName} does not exist or is null inside the DIT ${documentName} document!' >> ${DIT_deployment_name}_WARNINGS.txt"
        //sh "echo 'WARNING: !' >> ${DIT_deployment_name}_WARNINGS.txt"
        error("ERROR: The KEY ${keyName} does not exist or is null/empty inside the DIT ${documentName} document!")
    }
}

def readDitInformation() {
    echo "Pre Configurations: Reading values present in DIT"
    ditDeploymentUrlName = "https://atvdit.athtem.eei.ericsson.se/api/deployments?q=name%3D${DIT_deployment_name}"
    ditDocumentHead = 'https://atvdit.athtem.eei.ericsson.se/api/documents/'

    try{
        deploymentValue = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0].documents'",returnStdout: true).trim()
        //echo "${deploymentValue}"

        def deploymentValues = readJSON text: deploymentValue

        //find the ID of the cenm_deployment_values document
        for (document in deploymentValues){
            if (document.schema_name == "cenm_deployment_values") {
                env.deploymentValuesDocumentID = document.document_id
                echo "deployment_values DIT document ID is  : ${deploymentValuesDocumentID}"
            }
        }
    }
    catch (exception)
    {
        echo "ERROR:\n${exception}"
    }

    try{
        siteValuesDocumentID = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0].enm.sed_id'",returnStdout: true).trim()
        echo "cENM SED Document ID:  ${siteValuesDocumentID}"

        def site_values_document = sh(script: "curl -X GET https://atvdit.athtem.eei.ericsson.se/api/documents/${siteValuesDocumentID}", returnStdout: true).trim()
        def site_values_document_JSON = new JsonSlurper().parseText(site_values_document)

        env.ip_version = site_values_document_JSON.content.parameters.ip_version.toString()

        echo "----------- site_values document contents -----------"
        echo "${site_values_document_JSON}"

        //env.ingress_l4_cniMode = site_values_document_JSON.content.parameters.ingress_l4_cniMode
        //echo "The ingress_l4_cniMode (as per DIT) is: ${ingress_l4_cniMode}\n"
        //ditKeyCheck(ingress_l4_cniMode, "ingress_l4_cniMode" ,"site_values")

        env.rwx_storage_class_name = site_values_document_JSON.content.parameters.rwx_storageClass
        ditKeyCheck(rwx_storage_class_name, "rwx_storageClass" ,"site_values")

        env.enmHostURL = site_values_document_JSON.content.parameters.enmHost
        ditKeyCheck(enmHostURL, "enmHostURL" ,"site_values")

        //cENM VIP array list
        cENM_VIPsList = []

        echo ("IP version is set to [ ${site_values_document_JSON.content.parameters.ip_version.toString()} ] inside the DIT site_values document")

        switch (site_values_document_JSON.content.parameters.ip_version.toString()){
            case ["Dual", "IPv4"]:
                def fm_vip_address = site_values_document_JSON.content.parameters.fm_vip_address.toString()
                def cm_vip_address = site_values_document_JSON.content.parameters.cm_vip_address.toString()
                def pm_vip_address = site_values_document_JSON.content.parameters.pm_vip_address.toString()
                def amos_vip_address = site_values_document_JSON.content.parameters.amos_vip_address.toString()
                def general_scripting_vip_address = site_values_document_JSON.content.parameters.general_scripting_vip_address.toString()
                def visinamingsb_service = site_values_document_JSON.content.parameters.visinamingsb_service.toString()
                def svc_FM_vip_fwd_ipaddress = site_values_document_JSON.content.parameters.svc_FM_vip_fwd_ipaddress.toString()
                def itservices_0_vip_address = site_values_document_JSON.content.parameters.itservices_0_vip_address.toString()
                def itservices_1_vip_address = site_values_document_JSON.content.parameters.itservices_1_vip_address.toString()
                def apg_FM_vip_fwd_address = site_values_document_JSON.content.parameters.apg_FM_vip_fwd_address.toString()

                cENM_VIPsList = cENM_VIPsList + [
                        ["name": "fm_vip_address", "value": "${fm_vip_address}"],
                        ["name": "cm_vip_address", "value": "${cm_vip_address}"],
                        ["name": "pm_vip_address", "value": "${pm_vip_address}"],
                        ["name": "amos_vip_address", "value": "${amos_vip_address}"],
                        ["name": "general_scripting_vip_address", "value": "${general_scripting_vip_address}"],
                        ["name": "visinamingsb_service", "value": "${visinamingsb_service}"],
                        ["name": "svc_FM_vip_fwd_ipaddress", "value": "${svc_FM_vip_fwd_ipaddress}"],
                        ["name": "itservices_0_vip_address", "value": "${itservices_0_vip_address}"],
                        ["name": "itservices_1_vip_address", "value": "${itservices_1_vip_address}"],
                        ["name": "apg_FM_vip_fwd_address", "value": "${apg_FM_vip_fwd_address}"]
                ]

                env.LoadBalancerIPv4 = site_values_document_JSON.content.parameters.loadBalancerIP
                ditKeyCheck(LoadBalancerIPv4, "LoadBalancerIPv4" ,"site_values")
                //no break here so it falls to next case
            case ["Dual", "IPv6_EXT"]:
                if(site_values_document_JSON.content.parameters.ip_version.toString() != "IPv4"){
                    def svc_FM_vip_ipv6address = site_values_document_JSON.content.parameters.svc_fm_vip_ipv6address.toString()
                    def svc_CM_vip_ipv6address = site_values_document_JSON.content.parameters.svc_cm_vip_ipv6address.toString()
                    def svc_PM_vip_ipv6address = site_values_document_JSON.content.parameters.svc_pm_vip_ipv6address.toString()
                    def amos_service_IPv6_IPs = site_values_document_JSON.content.parameters.amos_service_ipv6_ips.toString()
                    def scripting_service_IPv6_IPs = site_values_document_JSON.content.parameters.scripting_service_ipv6_ips.toString()
                    def visinamingsb_service_IPv6_IPs = site_values_document_JSON.content.parameters.visinamingsb_service_ipv6_ips.toString()
                    def svc_FM_vip_fwd_ipv6address = site_values_document_JSON.content.parameters.svc_FM_vip_fwd_ipv6address.toString()
                    def itservices_service_0_IPv6_IPs = site_values_document_JSON.content.parameters.itservices_service_0_ipv6_ips.toString()
                    def itservices_service_1_IPv6_IPs = site_values_document_JSON.content.parameters.itservices_service_1_ipv6_ips.toString()
                    def apg_FM_vip_fwd_ipv6address = site_values_document_JSON.content.parameters.apg_FM_vip_fwd_ipv6address.toString()

                    //add cENM vips to list/map
                    cENM_VIPsList = cENM_VIPsList + [
                            ["name": "svc_FM_vip_ipv6address", "value": "${svc_FM_vip_ipv6address}"],
                            ["name": "svc_CM_vip_ipv6address", "value": "${svc_CM_vip_ipv6address}"],
                            ["name": "svc_PM_vip_ipv6address", "value": "${svc_PM_vip_ipv6address}"],
                            ["name": "amos_service_IPv6_IPs", "value": "${amos_service_IPv6_IPs}"],
                            ["name": "scripting_service_IPv6_IPs", "value": "${scripting_service_IPv6_IPs}"],
                            ["name": "visinamingsb_service_IPv6_IPs", "value": "${visinamingsb_service_IPv6_IPs}"],
                            ["name": "svc_FM_vip_fwd_ipv6address", "value": "${svc_FM_vip_fwd_ipv6address}"],
                            ["name": "itservices_service_0_IPv6_IPs", "value": "${itservices_service_0_IPv6_IPs}"],
                            ["name": "itservices_service_1_IPv6_IPs", "value": "${itservices_service_1_IPv6_IPs}"],
                            ["name": "apg_FM_vip_fwd_ipv6address", "value": "${apg_FM_vip_fwd_ipv6address}"]
                    ]

                    env.LoadBalancerIPv6 = site_values_document_JSON.content.parameters.loadBalancerIP_IPv6
                    ditKeyCheck(LoadBalancerIPv6, "LoadBalancerIPv6" ,"site_values")
                }
            case ["Dual", "IPv4", "IPv6_EXT"]:
                //output VIPs as per DIT onto console
                echo "\n\ncENM VIP IPs (as per DIT site_values document):"

                cENM_VIPsList.each { vip ->
                    ditKeyCheck("${vip.value}", "${vip.name}", "site_values")
                }
                break //break out of the switch
            default:
                error("ERROR: DIT site_values documents ip_version is not valid, please check DIT configuration!")
        }
    }
    catch (exception)
    {
        error("Error reading values from DIT site_values document!\nERROR: ${exception}")
    }

    try{
        def deployment_values_document = sh (script : "curl -X GET https://atvdit.athtem.eei.ericsson.se/api/documents/${deploymentValuesDocumentID}",returnStdout: true).trim()
        def deployment_values_document_JSON = new JsonSlurper().parseText(deployment_values_document)

        echo "----------- deployment_values document contents -----------"
        echo "${deployment_values_document_JSON}"

        //extract values
        env.enmNamespaceName = deployment_values_document_JSON.content.parameters.namespace
        env.enmContainerRegistryFQDN = deployment_values_document_JSON.content.parameters.registry_hostname

        ditKeyCheck(enmNamespaceName, "namespace" ,"deployment_values")
        ditKeyCheck(enmContainerRegistryFQDN, "registry_hostname" ,"deployment_values")
    }
    catch (exception){
        error("Error reading values from DIT site_deployment document!\nERROR: ${exception}")
    }

}


def getCCDVersion(){
    env.CCD_Version = sh (script : "${kubectl} version --short | grep 'Server Version' | awk '{print substr(\$3,2)}'", returnStdout: true).trim()
    printToElementalsReport("Cluster CCD Version:",CCD_Version)
}

def readClusterDimensionsData() {
    env.dimension_details = sh (script : "cat ${env.WORKSPACE}/cENM_FlexiKube_OpenStack_UAT/dimension_details.json", returnStdout: true).trim()
    def dimension_details_JSON = new JsonSlurper().parseText(dimension_details)
    echo "Cluster Dimensions details: ${dimension_details}"
    echo "Cluster Dimensions details of Orderable Item '${cluster_type}_${deployment_type}' (for the ${deployment_type} deployment type): ${dimension_details_JSON["${cluster_type}_${deployment_type}"]}"

    if("${deployment_type}" == "FlexiKube" || "${deployment_type}" == "OpenStack_IBD" || "${deployment_type}" == "OpenStack")
    {
        //extract expected master node details from dimension_details_JSON, based on the cluster_type (Orderable Item)
        env.expected_master_node_count = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].master.node_count}"
        env.expected_master_node_CPU = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].master.cpu}"
        env.expected_master_node_RAM = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].master.memory}"
        env.expected_master_node_storage = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].master.volume}"

        //extract expected worker node details from json based on the cluster_type (Orderable Item)
        env.expected_worker_node_count = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].worker.node_count}"
        env.expected_worker_node_CPU = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].worker.cpu}"
        env.expected_worker_node_RAM = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].worker.memory}"
        env.expected_worker_node_storage = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].worker.volume}"

        //only extract expected director node details from json if deployment is not OpenStack (CAPO)
        if("${deployment_type}" != "OpenStack")
        {
            env.expected_director_node_count = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].director.node_count}"
            env.expected_director_node_CPU = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].director.cpu}"
            env.expected_director_node_RAM = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].director.memory}"
            env.expected_director_node_storage = "${dimension_details_JSON["${cluster_type}_${deployment_type}"].director.volume}"
        }
    }
    else
    {
        sh "echo 'Error reading the cluster dimensions details of orderable item '${cluster_type}' (for the ${deployment_type} deployment type!' >> ${DIT_deployment_name}_WARNINGS.txt"
        error("Error reading the cluster dimensions details of orderable item '${cluster_type}' (for the ${deployment_type} deployment type!")
    }
}

def ccdLevelVersionCheck() {
    sh "${kubectl} get ns"
    output = ""
    totalcENMcount = sh (script : "${kubectl} get nodes | wc -l", returnStdout: true).trim()
    echo "Total Number of hosts present in this CCD cluster are - ${totalcENMcount}"
    int INTtotalcENMcount = Integer.parseInt(totalcENMcount)
    for(int i=1;i<INTtotalcENMcount;i++){
        hostsName = sh (script : "${kubectl} get nodes | grep -v STATUS | awk '(NR==$i)' | awk '{print \$1}'", returnStdout: true).trim()
        echo "${hostsName}"
        //sh "echo ${hostsName} >> ${DIT_deployment_name}_JIRA.txt"
        //sh "${kubectl} describe node ${hostsName} | grep 'ccd/version' >> ${DIT_deployment_name}_JIRA.txt"
        sh "${kubectl} describe node ${hostsName} | grep 'ccd/version'"
        def ccdVersion = sh (script : "${kubectl} describe node ${hostsName} | grep 'ccd/version'", returnStdout: true).trim()
        output += "\n${hostsName}\n${ccdVersion}\n"
    }
    printToElementalsReport("CCD Version Check",output)
}

def uploadPemFileDit_OpenStack(){
    deploymentHeadId = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0]'",returnStdout: true).trim()
    def myHeads = readJSON text: deploymentHeadId
    env.myHead = myHeads._id
    pemUrl = "https://atvdit.athtem.eei.ericsson.se/api/deployments/${myHead}"

    def testPem = sh (script : "curl -k -s -X GET ${pemUrl}",returnStdout: true).trim()
    def myGetPem = readJSON text: testPem
    contentName = myGetPem.name
    contentProjectId = myGetPem.project_id
    contentSedId = myGetPem.enm.sed_id

    //contentDummyKey = "-----BEGIN RSA PRIVATE KEY-----\r\nMIIEogIBAAKCAQEAutlfWK9+qBv+Msd3gbWDuScodoW1LExl2eOjSHRv/AnL+ucd\r\nHNRTwjOT5VzrEC9jf2COL5eu2t2gJCDkyDz59gJTv0o+XSH2p9LljbY7SEMfSK7c\r\nJ2PrQJbVdxKYcBErzGb8qf41+jG1P/fmD3jTeSA8hr6Dfe10BUsE+24EL9vNJW5j\r\n7dTDue+XlF3cf+hBTKMMy+U40CRFz5K8le6rV82VPnH0hoIhT72u/8vi30oqEzXG\r\n5x/OM/I2X6V0q+lvPvJkjUqktZn67PvhU+wcLjKmxxUrXFzz/dqeBIaPPMi8IAPk\r\nw/EUK031RxTQizVhwQAvbI1TzoyVKftlRvHeOwIDAQABAoIBAFAPbdRBNgLwI6Y8\r\nY493aB6AkczfE7cMcSPAbylPguA6jmVOe+HrdIwkr306qBnCRF7Cz4nC85AiIEj6\r\nsyy9O9lWO+4d8MTVFavpKKTk7VfUMuZgzkIuhRGiz4p6tEhogxzND/wCybwPansj\r\nTDda7TncPzL5FLxzbyAJefQFutOKHgdhjVgH7Um2NxGrQ6FoUSVajyoisAv+Yvvd\r\nhRfHniWI+3olprCCfDV7Vil4xSesVvFEofgsbGPZ4b3FMMkNqRWoLQDhyIKL6Nhr\r\ngORM+fG8UM5goJ9ZvTi6B2U3TruZ4qZc6I7D65/BlmFmm8wgMZ5alcs3KdQigdy6\r\npzMrTvkCgYEA5/2C+YD7gvHgV+Tbg2mz5o+Z1Z2hUAaOSn82xAyFRa5nleEDYEhH\r\nJb4dLOavOPqmmRM7ItDZENlxV2oSSoAVGptdVBgXi6GFkI6fDf1AeOap+yLrNAd2\r\nA50kiHR75vtv1fWwJnOb/abhknwXue6aB9EIRVdjx65gAV+txUzu9cUCgYEAzi/d\r\nJqOJNRjyD04yYB5YlAwydoGxCoV2qrHM/1W9+wQD3hkFS1ZL3CBb2u5NNAWlLKY0\r\nLEVVAHdyHXS5HJEzXmQHZa/yu2T5wV0885OD3EA/V3Zi2AjzClsC3LXEsqPIC4No\r\nh5vXAOCwfBwIxhtDIihhHgVmePEe8rx8r1I+w/8CgYAOOuqxy0uiOJv+SDd+1BkI\r\n534UMFsYwY4w26TMWchDAfOwqeC/Iy/aDNNVUcElyZo2gYt7Ezx9YBknt4Xvs/OX\r\ncjhDVEb9dabvuw/el85AnEWI9hdfVaXTiuwWwq5m+L1fbnajpSvIX1gu2BXMfepM\r\n2HGdb0LbmMKi0u+hzppJ0QKBgEyN7vGitJX3XiCaqw+PFNpbMP1ZJ++9IBM+ktuW\r\n7UPe+MSky5duQhpIFXLTGe0fz3UlfKeXUnkq4D7ZkMVvkAAS6cAytNApLKZDxRa3\r\nBbVoUVxbA1Ys9Hg61HQ4NQES2HqV3uDC1vBnfH+INSXBB4sOLQjlfmeXNyNvImhC\r\nBDXnAoGARQZ5PrNovdJYbBFGBzDOeVKvkrO++SYHFLlzpHADpkqY2uEQF2cQWDBu\r\nBvaQE7Yq19CDW2QgMwE7GTf1og2r8v0RgxOc5QeUdgNOADCuPNafvbyyzNzmgGjv\r\nPxY4r3DcvaJwW/TjwiIiyPkc6OALcUHLgGO7R2B5y+41oytYADA=\r\n-----END RSA PRIVATE KEY-----"
    contentDummyKey = "${env.pemFile}"
    echo "PEM KEYFILE TO ADD INTO DIT: ${env.pemFile}"
    unformatedPEM = "${env.pemFile}"
    formattedPEM = unformatedPEM.replaceAll('\n', "\\\\n")

    echo "FORMATTED PEM KEYFILE TO ADD INTO DIT: ${formattedPEM}"

    def content = """
    {
    "name": "${contentName}",
    "project_id": "${contentProjectId}",
    "enm": {
    "sed_id": "${contentSedId}",
    "private_key": "${formattedPEM}",
    "public_key": "${formattedPEM}"
    }
    }"""
    writeFile file: 'pemconfig/json_put_pem', text: content
}

def uploadPemFileDit_FlexiKube(){
    deploymentHeadId = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0]'",returnStdout: true).trim()
    def myHeads = readJSON text: deploymentHeadId
    env.myHead = myHeads._id
    pemUrl = "https://atvdit.athtem.eei.ericsson.se/api/deployments/${myHead}"

    def testPem = sh (script : "curl -k -s -X GET ${pemUrl}",returnStdout: true).trim()
    def myGetPem = readJSON text: testPem
    contentName = myGetPem.name
    contentProjectId = myGetPem.project_id
    contentSedId = myGetPem.enm.sed_id
    contentFlexiKubeKey = "-----BEGIN RSA PRIVATE KEY-----\\r\\nMIIEpAIBAAKCAQEA1\\/b4UUGk0NhvAorhq80BxxiNssZLLD0C597ZhnriOGuAdf3R\\r\\nnRWc8nwbpaXy99aKZC3gIpX0dhJPc7ukgKTQQhOq6DE1thKss+Ppk2R96oe6Xg\\/g\\r\\npVTixTH95QcSfQP60ynBuNyEV+ELZ98PrGw3lCUKxauJ3Y9Odf\\/V\\/fJhXCfftPU4\\r\\ngKOEsB7ncQ7rNdpJog77dGsCpjnFDC8zqVHCV2hcqvI3YVIw4RqzEn9BvBEgXoVL\\r\\nAMPg0\\/bW6dgHJgVsPFTdAuFGLejRbJhI7FQukCMij3BnVzd6Okz\\/JsBNGgH+5zHZ\\r\\nAywrV2T353rMSNFonD4di8YOXj4yX4UJDgKdEwIDAQABAoIBAQC3bUUlRrrkwx8u\\r\\nKqVX1OyQnJMlZ3RLo5pHNCjPJqnjP7NBBA63+7Zs4epdfCBsTeUHB0vaNEEI0651\\r\\n3sbumI1lweyj\\/7\\/d3+iddZNao7yqqRMqdxPXeMyOrlI15xbV5b5xAYNPLsSdG0Aj\\r\\nvfpC+TsPcZK\\/p12WN\\/RtFpk7clUs\\/gV8YyQKy7S8rBIjbrzoRSQmrV5cl8VGn25E\\r\\nhEazd2nlAEMajnT73kyKmqkBJX7PLLAJLMJIZRzhaIOw298gB2nKadTkCS2Y7+wm\\r\\nkKMueHuepBPmUlMMoMwGJLduw1NHvHJJTwATckHkl1eHSStFnwBZaXcfQyyOkPoO\\r\\n\\/8kAnkRpAoGBAO1wBNHHK8ndBW60A0FNmLWI8MKAIIVWk0IwSG0GSCwXAOvM4WYA\\r\\nslvBGoEnxfyZ9O6kgWdS2Vpl8tZ2eSP2KvH\\/ZeZMpcZnkM8OJmr1Q5iUbwLlQ785\\r\\nLGoUzhU3g50wJMftmBO4Ol2phAZwI2GhQusiJfO+Aq\\/YxSPrb5FLrsrXAoGBAOjZ\\r\\nM7+jp8F7fMfux+YfkJWodeipy0dig0FDHwyxDhQeNFNxdbhCnlTR0dQfpd3bdNYg\\r\\nesdbfAofa3HC\\/znjxJiE8q7Yyi72n3ADknGVA26YjYoQLnsY0JLAi+03ULrYZw8c\\r\\ncASqlzHk+GS9Z9eRICFRcsbqQNVp\\/tBf9Xn2\\/ZQlAoGAOhGXXCa10ty3I2frE+GC\\r\\nY4NmPmtPiMyvnxRn4iITLJVDqGenCGdLN512effcN\\/b\\/LA4Xh8l\\/VthwF3tKDT17\\r\\nK0wnA7fjIy7Y\\/4qaYrYxHfPPYonnk7DL5\\/XGoPG+woavuCWnd8sqmxWGMHzkalAi\\r\\nKZdkaMQjrBX7wNknpAU6bmkCgYEAlEVbEnkf5bDAsH94gy0uYF45VsJoUziD5Bbd\\r\\nurM0B9OD9m6VS5QARnqlZrIQaMnKCF\\/+TtwOjFOdk39cDnzfP0\\/JSVV6yZT5ydY8\\r\\ndl8xJEe4OWY8ct5GUmyRrag\\/m\\/sZBSJSomYOiRMIqP2DFl2vXAgFUmzwg\\/VO8Vlp\\r\\ncxS4PJECgYAK+XV3tCyK5ceuHy57drCb51a1KOb8br2cMBqNJbaWpjYyTps2x7DV\\r\\nZkZGJJrot\\/MattCG60eDq5Ryh6B7tl8nTmC1pOBu1kwHrmX8E1vqJrjHCaK9fDZN\\r\\n+StrK\\/KvKyD+MD1pF8wS\\/n7uZnn03p5na\\/trMv\\/vXtLv1ycFJ8+DjA==\\r\\n-----END RSA PRIVATE KEY-----"

    def content = """
    {
    "name": "${contentName}",
    "project_id": "${contentProjectId}",
    "enm": {
    "sed_id": "${contentSedId}",
    "private_key": "${contentFlexiKubeKey}",
    "public_key": "${contentFlexiKubeKey}"
    }
    }"""
    writeFile file: 'pemconfig/json_put_pem', text: content
}

def updateKubeconfigDIT() {
    ditDeploymentUrlName = 'https://atvdit.athtem.eei.ericsson.se/api/deployments?q=name%3D{$DIT_deployment_name}'
    ditDocumentHead = 'https://atvdit.athtem.eei.ericsson.se/api/documents/'

    deploymentValue = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0].documents'",returnStdout: true).trim()
    echo "${deploymentValue}"

    def deploymentValues = readJSON text: deploymentValue

    for (document in deploymentValues){
        if (document.schema_name == "cloud_native_enm_kube_config") {
            env.kubeConfigID = document.document_id
            echo "cloud_native_enm_kube_config DIT document ID is: ${kubeConfigID}"
        }
    }
    //gets template from dit, will need to update content of this
    def testConfig = sh (script : "curl -X GET https://atvdit.athtem.eei.ericsson.se/api/documents/${kubeConfigID}",returnStdout: true).trim()
    writeFile file: 'kubeconfig/json_get_config', text: testConfig

    try{
        //converts standard kube config file to json (from the workspace directory)
        def inputFile = readYaml file: 'kubeconfig/config'
        def json = new JsonBuilder(inputFile).toPrettyString()
        writeFile file: 'kubeconfig/config', text: json

        //reads template into file variable
        jsonfile = readJSON file: 'kubeconfig/json_get_config'
        kubejsoncontent = readJSON file: 'kubeconfig/config'
        jsonfile['content'] = kubejsoncontent
        writeFile file: 'kubeconfig/json_get_config', text: jsonfile.toString()

        return true
    }
    catch (exception){
        echo "\nError: ${exception}\n"
        echo "Error preparing the kubeconfig file contents from DTT for DIT update, check if kubeconfig file in DTT is formatted the same as clusters where update is working"
        return false
    }


}

def updateTafProperties() {
    ditDeploymentUrlName = 'https://atvdit.athtem.eei.ericsson.se/api/deployments?q=name%3D{$DIT_deployment_name}'
    ditDocumentHead = 'https://atvdit.athtem.eei.ericsson.se/api/documents/'
    deploymentValue = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0].documents'",returnStdout: true).trim()

    def deploymentValues = readJSON text: deploymentValue
    //loops trough the available DIT documents and checks if cENM_TAF_Properties is present
    for (document in deploymentValues){
        if (document.schema_name == "cENM_TAF_Properties") {
            env.tafPropertiesID = document.document_id
            echo "cENM_TAF_Properties DIT document ID is  : ${tafPropertiesID}"
        }
    }

    if ("${env.tafPropertiesID}" == "null"){
        sh "echo 'WARNING: cENM_TAF_Properties DIT document has not been found, skipping update!' >> ${DIT_deployment_name}_WARNINGS.txt"
        unstable("cENM_TAF_Properties DIT document has not been found, skipping update, please check!")
        return false
    }

    //gets template from dit, will need to update content of this
    def testConfig = sh (script : "curl -X GET https://atvdit.athtem.eei.ericsson.se/api/documents/${tafPropertiesID}",returnStdout: true).trim()

    //converts output to JSON
    tafPropertiesValues = readJSON text: testConfig
    echo "Current DIT cENM_TAF_Properties Client Machine / Director Node Details: ${tafPropertiesValues.content.global.client_machine}"

    //If deployment type is OpenStack (CAPO) then skip the DIT Taf properties document updates
    if("${deployment_type}" == "OpenStack"){
        echo "Deployment type is OpenStack CAPO...."
        if(tafPropertiesValues.content.global.client_machine.type == "slave"){
            echo "DIT cENM_TAF_Properties [client_machine.type] key is configured with [slave]. Please ensure that the currently provided IP [${tafPropertiesValues.content.global.client_machine.ipaddress}] matches with the new CAPO Client VM"
            sh "echo 'WARNING: Please ensure that the currently provided IP [${tafPropertiesValues.content.global.client_machine.ipaddress}] matches with the new CAPO Client VM, this needs to be checked manually!' >> ${DIT_deployment_name}_WARNINGS.txt"
        }
        else{
            sh "echo 'WARNING: Please check the [client_machine.type] key provided inside the cENM_TAF_Properties DIT document!' >> ${DIT_deployment_name}_WARNINGS.txt"
            unstable("Please check the [client_machine.type] key provided inside the cENM_TAF_Properties DIT document!")
        }

        return false
    }

    if(tafPropertiesValues.content.global.client_machine.type == "slave"){
        echo "Document is configured with Director Node VM details."

        if(tafPropertiesValues.content.global.client_machine.ipaddress == "${directorNodeIP}"){
            echo "Director Node IP is already up-to-date, skipping update..."
            return false
        }
        else{
            echo "Updating the Director node IP from ${tafPropertiesValues.content.global.client_machine.ipaddress} to ${directorNodeIP}"
            writeFile file: 'tafProperties/json_get_properties', text: testConfig

            def inputFile = readJSON text: testConfig
            def json = new JsonBuilder(inputFile).toPrettyString()

            writeFile file: 'tafProperties/json_get_properties', text: json

            //reads template into file variable
            jsonfile = readJSON file: 'tafProperties/json_get_properties'
            tafPropertiesContent = readJSON file: 'tafProperties/json_get_properties'

            //update the director node IP of the taf_properties file (updated file will be used for DIT update)
            tafPropertiesContent.content.global.client_machine.ipaddress = directorNodeIP
            writeFile file: 'tafProperties/json_get_properties', text: tafPropertiesContent.toString()

            return true
        }
    }
    else if(tafPropertiesValues.content.global.client_machine.type == "client_machine"){
        echo "Document is configured with Client Machine details, skipping update..."
        return false
    }
}

def updateSiteInformationDocument() {
    ditDeploymentUrlName = 'https://atvdit.athtem.eei.ericsson.se/api/deployments?q=name%3D{$DIT_deployment_name}'
    ditDocumentHead = 'https://atvdit.athtem.eei.ericsson.se/api/documents/'
    deploymentValue = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0].documents'",returnStdout: true).trim()

    def deploymentValues = readJSON text: deploymentValue
    //loops trough the available DIT documents and checks if cENM_site_information is present
    for (document in deploymentValues){
        if (document.schema_name == "cENM_site_information") {
            env.site_informationID = document.document_id
            echo "cENM_site_information DIT document ID is  : ${site_informationID}"
        }
    }

    //gets template from dit, will need to update content of this
    def testConfig = sh (script : "curl -X GET https://atvdit.athtem.eei.ericsson.se/api/documents/${site_informationID}",returnStdout: true).trim()

    //converts output to JSON
    siteInformationValues = readJSON text: testConfig
    echo "Current DIT cENM_site_information Client Machine / Director Node Details: ${siteInformationValues.content.global.client_machine}"

    if(siteInformationValues.content.global.client_machine.type == "slave"){
        echo "Document is configured with Director Node VM details."

        if(siteInformationValues.content.global.client_machine.ipaddress == "${directorNodeIP}"){
            echo "Director Node IP is already up-to-date, skipping update..."
            return false
        }
        else{
            echo "Updating the Director node IP from ${siteInformationValues.content.global.client_machine.ipaddress} to ${directorNodeIP}"
            writeFile file: 'siteInformation/json_get_properties', text: testConfig

            def inputFile = readJSON text: testConfig
            def json = new JsonBuilder(inputFile).toPrettyString()

            writeFile file: 'siteInformation/json_get_properties', text: json

            //reads template into file variable
            jsonfile = readJSON file: 'siteInformation/json_get_properties'
            site_informationContent = readJSON file: 'siteInformation/json_get_properties'

            //update the director node IP of the taf_properties file (updated file will be used for DIT update)
            site_informationContent.content.global.client_machine.ipaddress = directorNodeIP
            writeFile file: 'siteInformation/json_get_properties', text: site_informationContent.toString()

            return true
        }
    }
    else if(siteInformationValues.content.global.client_machine.type == "client_machine"){
        echo "Document is configured with Client Machine details, skipping update..."
        return false
    }
}

def clusterSoftwareCheck() {

    def kubectlVersion = sh (script : "${directorNodeSSH} kubectl version", returnStdout: true).trim()
    echo "${kubectlVersion}"
    //sh "echo '\n${kubectlVersion}\n' >> ${DIT_deployment_name}_JIRA.txt"
    printToElementalsReport("Kubectl Version Check",kubectlVersion)

    /*
    def dockerVersion = sh (script : "${directorNodeSSH} sudo docker version", returnStdout: true).trim()
    echo "${dockerVersion}"
    //sh "echo '\n${dockerVersion}\n' >> ${DIT_deployment_name}_JIRA.txt"
    printToElementalsReport("Docker Version Check",dockerVersion)

     */

    def helmVersion = sh (script : "${directorNodeSSH} helm version", returnStdout: true).trim()
    echo "${helmVersion}"
    //sh "echo '\n${helmVersion}\n' >> ${DIT_deployment_name}_JIRA.txt"
    printToElementalsReport("Helm Version Check",helmVersion)


}

def storageClassCheckFlexiKube(){
    //Check to see if the "csi-cephfs-sc" storage class exists
    def storageClass =  sh (script : "${kubectl} get sc", returnStdout: true).trim()
    boolean doesCephStorageClassExist = storageClass.contains("csi-cephfs-sc")

    if (doesCephStorageClassExist){
        printToElementalsReport("csi-cephfs-sc storage class is present on the cluster",storageClass)
    }
    else{
        printToElementalsReport("csi-cephfs-sc storage class is not present on the cluster!",storageClass)
        sh "echo 'WARNING: csi-cephfs-sc storage class is not present on the cluster!\n${storageClass}' >> ${DIT_deployment_name}_WARNINGS.txt"
        unstable("csi-cephfs-sc storage class is not present on the cluster, please check!")
    }
}

def clusterNodesCheck(){
    //Check to see if all nodes are in Running status
    def nodesList =  sh (script : "${kubectl} get nodes -o wide", returnStdout: true).trim()
    boolean doesContainNotReadyNodes = nodesList.contains("NotReady")

    if (doesContainNotReadyNodes){
        printToElementalsReport("Cluster Nodes","WARNING: One or more nodes are in NotReady status!\n${nodesList}")
        sh "echo 'WARNING: One or more nodes are in NotReady status!\n${nodesList}' >> ${DIT_deployment_name}_WARNINGS.txt"
        unstable("WARNING: One or more nodes are in NotReady status, please check!")
    }
    else{
        echo "All worker/master nodes are in Ready status"
        printToElementalsReport("Cluster Nodes",nodesList)}
}

def clusterPodsCheck(){
    def notRunningPods =  sh (script : "${kubectl} get pods -A | grep -v Completed | grep -v STATUS | grep -v 1/1 | grep -v 2/2 | grep -v 3/3 | grep -v 4/4 | grep -v 5/5 | grep -v 6/6 | grep -v 7/7 || true", returnStdout: true).trim()

    if (notRunningPods == null || notRunningPods == "")
        echo "Pods on all namespaces are in fully Running/Completed status"
    else {
        sh "echo 'WARNING: One or multiple pods are not in fully Running/Completed status status!\nPlease find the respective pods below:\n${notRunningPods}' >> ${DIT_deployment_name}_WARNINGS.txt"
        unstable("WARNING Cluster has Pods not in fully Ready status, please check!")
    }
}


def clusterAllocatedResourcesCheck(){
    env.isResourceNotMatching = false //flag used to determine if cluster resources match the O.I

    totalcENMcount = sh (script : "${kubectl} get nodes | wc -l", returnStdout: true).trim()
    echo "\nTotal Number of hosts present in this CCD cluster are - ${totalcENMcount}\n"

    int INTtotalcENMcount = Integer.parseInt(totalcENMcount)

    def allocatedNodeResourcesList = []

    //get the director node resources
    def directorNodeCPU = sh (script : "${directorNodeSSH} lscpu | grep '^CPU(s):' | awk '{print \$2}'", returnStdout: true).trim()
    echo "Director Node CPU: ${directorNodeCPU}"

    def directorNodeRAM = sh (script : "${directorNodeSSH} free -g | grep '^Mem:' | awk '{print \$2}'", returnStdout: true).trim()
    echo "Director Node RAM: ${directorNodeRAM}GB"

    def directorNodeStorage = sh (script : "${directorNodeSSH} df -h | grep '^/dev/vda3' | awk '{print \$2}'", returnStdout: true).trim()
    directorNodeStorage = directorNodeStorage.substring(0, directorNodeStorage.size()-1)
    echo "Director Node Storage: ${directorNodeStorage}GB"

    //add the director node resources to linkedHashMap and then add the map to the allocated node resources list with the rest of the nodes
    def allocatedDirectorNodeResources = [:]
    allocatedDirectorNodeResources['name'] = "director1"
    allocatedDirectorNodeResources['cpu'] = "${directorNodeCPU}"
    allocatedDirectorNodeResources['ram'] = "${directorNodeRAM}"
    allocatedDirectorNodeResources['storage'] = "${directorNodeStorage}"
    allocatedNodeResourcesList << allocatedDirectorNodeResources
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    //Loop trough all master/worker nodes and find their details
    for(int i=1;i<INTtotalcENMcount;i++){
        def nodeName = sh (script : "${kubectl} get nodes | grep -v STATUS | awk '(NR==$i)' | awk '{print \$1}'", returnStdout: true).trim()

        //grab output of the respective nodes describe command
        def describeNodeOutput =  sh (script : "${kubectl} describe node ${nodeName}", returnStdout: true).trim()

        //extract each respective resource value onto its own variable
        def cpu = describeNodeOutput =~ /cpu:\s+([^\s]+)/
        def ram = describeNodeOutput =~ /memory:\s+([^\s]+)/
        def volume_storage = describeNodeOutput =~ /ephemeral-storage:\s+([^\s]+)/

        //convert the RAM and storage values from Ki to GB
        def ramConverted = 0
        if (ram[0][1].contains("Ki"))
            ramConverted = Math.ceil((ram[0][1].replaceAll("Ki","") as Double) / 1024.0 / 1024.0)
        else
            ramConverted = Math.ceil((ram[0][1].replaceAll("Mi","") as Double) / 1024.0)

        def volumeStorageConverted = 0
        if (volume_storage[0][1].contains("Ki"))
            volumeStorageConverted = Math.ceil((volume_storage[0][1].replaceAll("Ki","") as Double) / 1024.0 / 1024.0)
        else
            volumeStorageConverted = Math.ceil((volume_storage[0][1].replaceAll("Mi","") as Double) / 1024.0)

        //store each node resources in linkedHashMap
        def allocatedNodeResources = [:]
        allocatedNodeResources['name'] = "${nodeName}"
        allocatedNodeResources['cpu'] = "${cpu[0][1]}"
        allocatedNodeResources['ram'] = "${ramConverted}"
        allocatedNodeResources['storage'] = "${volumeStorageConverted}"

        //add the allocatedNodeResources values to the allocatedNodeResourcesList
        allocatedNodeResourcesList << allocatedNodeResources

        /*
        //Output to console
        echo "Name: ${allocatedNodeResources['name']}"
        echo "Actual CPU: ${allocatedNodeResources['cpu']}"
        echo "Actual RAM: ${allocatedNodeResources['ram']}GB"
        echo "Actual Storage: ${allocatedNodeResources['storage']}GB"
        */
    }

    //get master/worker node count and output it
    totalMasterNodeCount = sh (script : "${kubectl} get nodes | grep master | wc -l", returnStdout: true).trim()
    //echo "Total Number of master nodes present in this cluster: ${totalMasterNodeCount}"
    //sh "echo \"Total Number of master nodes present in this cluster: ${totalMasterNodeCount}\" >> ${DIT_deployment_name}_JIRA.txt"

    if(totalMasterNodeCount == "0"){
        totalMasterNodeCount = sh (script : "${kubectl} get nodes | grep controlplane | wc -l", returnStdout: true).trim()
    }

    printToElementalsReport("Master Node Count",totalMasterNodeCount)

    totalWorkerNodeCount = sh (script : "${kubectl} get nodes | grep worker | wc -l", returnStdout: true).trim()
    echo "Total Number of worker nodes present in this cluster: ${totalWorkerNodeCount}"
    //sh "echo \"Total Number of worker nodes present in this cluster: ${totalWorkerNodeCount}\n\" >> ${DIT_deployment_name}_JIRA.txt"
    printToElementalsReport("Worker Node Count",totalWorkerNodeCount)

    echo "\nCOMPARING CLUSTER RESOURCES AGAINST THE EXPECTED DIMENSIONS (ORDERABLE ITEM)"

    output = ""
    boolean isResourceMatching = true

    //compare the master/worker node count between the O.I
    def masterNodeCountCheck = compareTwoResources("${expected_master_node_count}","${totalMasterNodeCount}","Master node count")
    output += "${masterNodeCountCheck[0]}"

    def workerNodeCountCheck = compareTwoResources("${expected_worker_node_count}","${totalWorkerNodeCount}","Worker node count")
    output += "${workerNodeCountCheck[0]}"


    //loop trough all the nodes inside allocatedNodeResourcesList
    allocatedNodeResourcesList.each { allocatedNodeResources ->
        //sh "echo '\n\nNode Name: ${allocatedNodeResources['name']}' >> ${DIT_deployment_name}_JIRA.txt"
        output += "\n\nNode Name: ${allocatedNodeResources['name']}"
        //sh "echo '\nName: ${allocatedNodeResources['name']}\nCPU: ${allocatedNodeResources['cpu']}\nRAM: ${allocatedNodeResources['ram']}GB\nStorage: ${allocatedNodeResources['storage']}GB' >> ${DIT_deployment_name}_JIRA.txt"

        if(allocatedNodeResources['name'].contains("director")){
            def result1 = compareTwoResources("${expected_director_node_CPU}","${allocatedNodeResources['cpu']}","Director node CPU")
            output += "${result1[0]}"

            def result2 = compareTwoResources("${expected_director_node_RAM.toDouble()}","${allocatedNodeResources['ram'].toDouble()}","Director node RAM")
            output += "${result2[0]}"

            def result3 = compareTwoResources("${expected_director_node_storage.toDouble()}","${allocatedNodeResources['storage'].toDouble()}","Director node Volume Storage")
            output += "${result3[0]}"

            if (result1[1]==false || result2[1]==false || result3[1]==false )
                isResourceMatching = false
        }
        else if(allocatedNodeResources['name'].contains("master")){
            def result1 = compareTwoResources("${expected_master_node_CPU}","${allocatedNodeResources['cpu']}","Master node CPU")
            output += "${result1[0]}"

            def result2 = compareTwoResources("${expected_master_node_RAM.toDouble()}","${allocatedNodeResources['ram'].toDouble()}","Master node RAM")
            output += "${result2[0]}"

            def result3 = compareTwoResources("${expected_master_node_storage.toDouble()}","${allocatedNodeResources['storage'].toDouble()}","Master node Volume Storage")
            output += "${result3[0]}"

            if (result1[1]==false || result2[1]==false || result3[1]==false )
                isResourceMatching = false
        }
        else if(allocatedNodeResources['name'].contains("controlplane")){
            def result1 = compareTwoResources("${expected_master_node_CPU}","${allocatedNodeResources['cpu']}","Control-Plane node CPU")
            output += "${result1[0]}"

            def result2 = compareTwoResources("${expected_master_node_RAM.toDouble()}","${allocatedNodeResources['ram'].toDouble()}","Control-Plane node RAM")
            output += "${result2[0]}"

            def result3 = compareTwoResources("${expected_master_node_storage.toDouble()}","${allocatedNodeResources['storage'].toDouble()}","Control-Plane node Volume Storage")
            output += "${result3[0]}"

            if (result1[1]==false || result2[1]==false || result3[1]==false )
                isResourceMatching = false
        }
        else if(allocatedNodeResources['name'].contains("worker")){
            def result1 = compareTwoResources("${expected_worker_node_CPU}","${allocatedNodeResources['cpu']}","Worker node CPU")
            output += "${result1[0]}"

            def result2 = compareTwoResources("${expected_worker_node_RAM.toDouble()}","${allocatedNodeResources['ram'].toDouble()}","Worker node RAM")
            output += "${result2[0]}"

            def result3 = compareTwoResources("${expected_worker_node_storage.toDouble()}","${allocatedNodeResources['storage'].toDouble()}","Worker node Volume Storage")
            output += "${result3[0]}"

            if (result1[1]==false || result2[1]==false || result3[1]==false )
                isResourceMatching = false
        }
        else{
            echo "\nError! Could not find node type of '${allocatedNodeResources['name']}'"
            output += "\nError! Could not find node type of '${allocatedNodeResources['name']}'"
        }


    }
    printToElementalsReport("Cluster Allocated resources", "${output}")

    if (!isResourceMatching){
        echo "\nNot all cluster resources are matching the O.I\n"
        return false
    }
    else
        echo "\nAll cluster resources correctly match the O.I\n"
}

def clusterAllocatedResourcesCheckOpenStack(){
    env.isResourceNotMatching = false //flag used to determine if cluster resources match the O.I

    totalcENMcount = sh (script : "${kubectl} get nodes | wc -l", returnStdout: true).trim()
    echo "\nTotal Number of hosts present in this CCD cluster are - ${totalcENMcount}\n"

    int INTtotalcENMcount = Integer.parseInt(totalcENMcount)

    def allocatedNodeResourcesList = []

    if("${deployment_type}" == "FlexiKube" || "${deployment_type}" == "OpenStack_IBD"){
        //get the director node resources
        def directorNodeCPU = sh (script : "${directorNodeSSH} lscpu | grep '^CPU(s):' | awk '{print \$2}'", returnStdout: true).trim()
        echo "Director Node CPU: ${directorNodeCPU}"

        def directorNodeRAM = sh (script : "${directorNodeSSH} free -g | grep '^Mem:' | awk '{print \$2}'", returnStdout: true).trim()
        echo "Director Node RAM: ${directorNodeRAM}GB"

        def directorNodeStorage = sh (script : "${directorNodeSSH} df -h | grep '^/dev/vda3' | awk '{print \$2}'", returnStdout: true).trim()
        directorNodeStorage = directorNodeStorage.substring(0, directorNodeStorage.size()-1)
        echo "Director Node Storage: ${directorNodeStorage}GB"

        //add the director node resources to linkedHashMap and then add the map to the allocated node resources list with the rest of the nodes
        def allocatedDirectorNodeResources = [:]
        allocatedDirectorNodeResources['name'] = "director1"
        allocatedDirectorNodeResources['cpu'] = "${directorNodeCPU}"
        allocatedDirectorNodeResources['ram'] = "${directorNodeRAM}"
        allocatedDirectorNodeResources['storage'] = "${directorNodeStorage}"
        allocatedNodeResourcesList << allocatedDirectorNodeResources
    }
    else{
        echo "Skipping director node check for deployment type '${deployment_type}'"
    }


    //Loop trough all master/worker nodes and find their details
    for(int i=1;i<INTtotalcENMcount;i++){
        def nodeName = sh (script : "${kubectl} get nodes | grep -v STATUS | awk '(NR==$i)' | awk '{print \$1}'", returnStdout: true).trim()

        //grab output of the respective nodes describe command
        def describeNodeOutput =  sh (script : "${kubectl} describe node ${nodeName}", returnStdout: true).trim()

        //extract each respective resource value onto its own variable
        def cpu = describeNodeOutput =~ /cpu:\s+([^\s]+)/
        def ram = describeNodeOutput =~ /memory:\s+([^\s]+)/
        def volume_storage = describeNodeOutput =~ /ephemeral-storage:\s+([^\s]+)/

        //convert the RAM and storage values from Ki to GB
        def ramConverted = 0
        if (ram[0][1].contains("Ki"))
            ramConverted = Math.ceil((ram[0][1].replaceAll("Ki","") as Double) / 1024.0 / 1024.0)
        else
            ramConverted = Math.ceil((ram[0][1].replaceAll("Mi","") as Double) / 1024.0)

        def volumeStorageConverted = 0
        if (volume_storage[0][1].contains("Ki"))
            volumeStorageConverted = Math.ceil((volume_storage[0][1].replaceAll("Ki","") as Double) / 1024.0 / 1024.0)
        else
            volumeStorageConverted = Math.ceil((volume_storage[0][1].replaceAll("Mi","") as Double) / 1024.0)

        //store each node resources in linkedHashMap
        def allocatedNodeResources = [:]
        allocatedNodeResources['name'] = "${nodeName}"
        allocatedNodeResources['cpu'] = "${cpu[0][1]}"
        allocatedNodeResources['ram'] = "${ramConverted}"
        allocatedNodeResources['storage'] = "${volumeStorageConverted}"

        //add the allocatedNodeResources values to the allocatedNodeResourcesList
        allocatedNodeResourcesList << allocatedNodeResources
    }

    //get master/worker node count and output it
    totalMasterNodeCount = sh (script : "${kubectl} get nodes | grep master | wc -l", returnStdout: true).trim()

    if(totalMasterNodeCount == "0"){
        totalMasterNodeCount = sh (script : "${kubectl} get nodes | grep controlplane | wc -l", returnStdout: true).trim()
    }

    printToElementalsReport("Master Node Count",totalMasterNodeCount)

    totalWorkerNodeCount = sh (script : "${kubectl} get nodes | grep worker | wc -l", returnStdout: true).trim()
    echo "Total Number of worker nodes present in this cluster: ${totalWorkerNodeCount}"
    //sh "echo \"Total Number of worker nodes present in this cluster: ${totalWorkerNodeCount}\n\" >> ${DIT_deployment_name}_JIRA.txt"
    printToElementalsReport("Worker Node Count",totalWorkerNodeCount)

    echo "\nCOMPARING CLUSTER RESOURCES AGAINST THE EXPECTED DIMENSIONS (ORDERABLE ITEM)"

    output = ""

    //compare the master/worker node count between the O.I
    def masterNodeCountCheck = compareTwoResources("${expected_master_node_count}","${totalMasterNodeCount}","Master node count")
    output += "${masterNodeCountCheck[0]}"

    def workerNodeCountCheck = compareTwoResources("${expected_worker_node_count}","${totalWorkerNodeCount}","Worker node count")
    output += "${workerNodeCountCheck[0]}"

    if (masterNodeCountCheck[1]==false || workerNodeCountCheck[1]==false){
        unstable("WARNING: Master/Worker Node count does not match the Orderable Item / Cluster Dimensions, please check!")
    }

    //loop trough all the nodes inside allocatedNodeResourcesList
    allocatedNodeResourcesList.each { allocatedNodeResources ->
        //sh "echo '\n\nNode Name: ${allocatedNodeResources['name']}' >> ${DIT_deployment_name}_JIRA.txt"
        output += "\n\nNode Name: ${allocatedNodeResources['name']}"
        //sh "echo '\nName: ${allocatedNodeResources['name']}\nCPU: ${allocatedNodeResources['cpu']}\nRAM: ${allocatedNodeResources['ram']}GB\nStorage: ${allocatedNodeResources['storage']}GB' >> ${DIT_deployment_name}_JIRA.txt"

        if(allocatedNodeResources['name'].contains("director")){
            def result1 = compareTwoResources("${expected_director_node_CPU}","${allocatedNodeResources['cpu']}","Director node CPU")
            output += "${result1[0]}"

            def result2 = compareTwoResources("${expected_director_node_RAM.toDouble()}","${allocatedNodeResources['ram'].toDouble()}","Director node RAM")
            output += "${result2[0]}"

            def result3 = compareTwoResources("${expected_director_node_storage.toDouble()}","${allocatedNodeResources['storage'].toDouble()}","Director node Volume Storage")
            output += "${result3[0]}"

            if (result1[1]==false || result2[1]==false || result3[1]==false )
                unstable("Director node resources do not match the Orderable Item / Cluster Dimensions, please check!")
        }
        else if(allocatedNodeResources['name'].contains("master")){
            def result1 = compareTwoResources("${expected_master_node_CPU}","${allocatedNodeResources['cpu']}","Master node CPU")
            output += "${result1[0]}"
            def result2 = compareTwoResources("${expected_master_node_RAM.toDouble()}","${allocatedNodeResources['ram'].toDouble()}","Master node RAM")
            output += "${result2[0]}"
            def result3 = compareTwoResources("${expected_master_node_storage.toDouble()}","${allocatedNodeResources['storage'].toDouble()}","Master node Volume Storage")
            output += "${result3[0]}"

            if (result1[1]==false || result2[1]==false || result3[1]==false )
                unstable("Master Node resources do not match the Orderable Item / Cluster Dimensions, please check!")
        }
        else if(allocatedNodeResources['name'].contains("controlplane")){
            def result1 = compareTwoResources("${expected_master_node_CPU}","${allocatedNodeResources['cpu']}","Control-Plane node CPU")
            output += "${result1[0]}"
            def result2 = compareTwoResources("${expected_master_node_RAM.toDouble()}","${allocatedNodeResources['ram'].toDouble()}","Control-Plane node RAM")
            output += "${result2[0]}"
            def result3 = compareTwoResources("${expected_master_node_storage.toDouble()}","${allocatedNodeResources['storage'].toDouble()}","Control-Plane node Volume Storage")
            output += "${result3[0]}"

            if (result1[1]==false || result2[1]==false || result3[1]==false )
                unstable("Control-plane node resources do not match the Orderable Item / Cluster Dimensions, please check!")
        }
        else if(allocatedNodeResources['name'].contains("worker")){
            def result1 = compareTwoResources("${expected_worker_node_CPU}","${allocatedNodeResources['cpu']}","Worker node CPU")
            output += "${result1[0]}"
            def result2 = compareTwoResources("${expected_worker_node_RAM.toDouble()}","${allocatedNodeResources['ram'].toDouble()}","Worker node RAM")
            output += "${result2[0]}"
            def result3 = compareTwoResources("${expected_worker_node_storage.toDouble()}","${allocatedNodeResources['storage'].toDouble()}","Worker node Volume Storage")
            output += "${result3[0]}"

            if (result1[1]==false || result2[1]==false || result3[1]==false )
                unstable("Worker node resources do not match the Orderable Item / Cluster Dimensions, please check!")
        }
        else{
            echo "\nError! Could not find node type of '${allocatedNodeResources['name']}'"
            output += "\nError! Could not find node type of '${allocatedNodeResources['name']}'"
        }
    }
    printToElementalsReport("Cluster Allocated resources", "${output}")
}

//Helper function that compares an expected cluster resource value to the actual resource value
def compareTwoResources(expected,actual,comparisonText){
    try{
        if(expected == actual){
            return ["\n${comparisonText} matches the orderable item!\nExpected value: ${expected}\nActual value: ${actual}", true]
        }
        else {
            echo "WARNING: ${comparisonText} does not match the orderable item!\nExpected value: ${expected}\nActual value: ${actual}"
            sh "echo 'WARNING: ${comparisonText} does not match the orderable item!\nExpected value: ${expected}\nActual value: ${actual}' >> ${DIT_deployment_name}_WARNINGS.txt"
            return ["\nWARNING: ${comparisonText} does not match the orderable item!\nExpected value: ${expected}\nActual value: ${actual}", false]
        }
    }
    catch (exception){
        echo "\nError: ${exception}\n"
    }

}

def createNamespaces(){
    def checkNamespaces = sh (script : "${kubectl} get ns", returnStdout: true).trim()

    boolean doesContainNamespaceForENM = checkNamespaces.contains("${enmNamespaceName}")

    if (doesContainNamespaceForENM)
        echo "Namespace '${enmNamespaceName}' already exists:\n${checkNamespaces}"
    else{
        def createNamespaceForENM = sh (script : "${kubectl} create ns ${enmNamespaceName}", returnStdout: true).trim()
        echo "${createNamespaceForENM}"
    }
    def checkNamespace = sh (script : "${kubectl} get ns", returnStdout: true).trim()
    printToElementalsReport("Cluster Namespaces",checkNamespace)
}

def createInventoryFile(){
    //remove inventory file if exists
    def remove_inventory_file = sh (script : "${directorNodeSSH} sudo rm /home/${clusterOwnerSignum}/inventory || true", returnStdout: true).trim()
    echo "${remove_inventory_file}"

    //generate new inventory file with up-to-date node names
    def create_inventory_file = sh (script : "${directorNodeSSH} \"kubectl get node -o wide | awk '{print \\\$1,\\\"\\\",\\\"ansible_host=\\\"\\\$6}' > inventory\"", returnStdout: true).trim()
    echo "${create_inventory_file}"

    def inventory_file_output = sh (script : "${directorNodeSSH} cat /home/${clusterOwnerSignum}/inventory", returnStdout: true).trim()
    echo "Newly creatd inventory file contents:\n${inventory_file_output}"

    //update the inventory file with the required parameters
    def update_inventory_file1 = sh (script : "${directorNodeSSH} sed -i '2i\\[master]' inventory", returnStdout: true).trim()
    def update_inventory_file2 = sh (script : "${directorNodeSSH} sed -i '6i\\[workers:children]\\\\n\\worker\\\\n[worker]' inventory", returnStdout: true).trim()
    def update_inventory_file3 = sh (script : "${directorNodeSSH} \"printf \\\"[all:vars]\\\" >> inventory\"", returnStdout: true).trim()
    def update_inventory_file4 = sh (script : "${directorNodeSSH} \"printf \\\"\\\\nansible_python_interpreter=/usr/bin/python3\\\" >> inventory\"", returnStdout: true).trim()
    def update_inventory_file5 = sh (script : "${directorNodeSSH} \"printf \\\"\\\\nansible_ssh_common_args='-o StrictHostKeyChecking=no'\\\\n\\\" >> inventory\"", returnStdout: true).trim()

    def inventory_file_output2 = sh (script : "${directorNodeSSH} cat /home/${clusterOwnerSignum}/inventory", returnStdout: true).trim()
    echo "Updated inventory file contents:\n${inventory_file_output2}"
}

def createInventoryFileOpenStack(){
    //remove inventory file if exists
    def remove_inventory_file = sh (script : "${directorNodeSSH} sudo rm /home/eccd/inventory || true", returnStdout: true).trim()
    echo "${remove_inventory_file}"

    //generate new inventory file with up-to-date node names
    def create_inventory_file = sh (script : "${directorNodeSSH} \"kubectl get node -o wide | awk '{print \\\$1,\\\"\\\",\\\"ansible_host=\\\"\\\$6}' > inventory\"", returnStdout: true).trim()
    echo "${create_inventory_file}"

    def inventory_file_output = sh (script : "${directorNodeSSH} cat /home/eccd/inventory", returnStdout: true).trim()
    echo "Newly creatd inventory file contents:\n${inventory_file_output}"

    //update the inventory file with the required parameters
    def update_inventory_file1 = sh (script : "${directorNodeSSH} sed -i '2i\\[master]' inventory", returnStdout: true).trim()
    def update_inventory_file2 = sh (script : "${directorNodeSSH} sed -i '6i\\[workers:children]\\\\n\\worker\\\\n[worker]' inventory", returnStdout: true).trim()
    def update_inventory_file3 = sh (script : "${directorNodeSSH} \"printf \\\"[all:vars]\\\" >> inventory\"", returnStdout: true).trim()
    def update_inventory_file4 = sh (script : "${directorNodeSSH} \"printf \\\"\\\\nansible_python_interpreter=/usr/bin/python3\\\" >> inventory\"", returnStdout: true).trim()
    def update_inventory_file5 = sh (script : "${directorNodeSSH} \"printf \\\"\\\\nansible_ssh_common_args='-o StrictHostKeyChecking=no'\\\\n\\\" >> inventory\"", returnStdout: true).trim()

    def inventory_file_output2 = sh (script : "${directorNodeSSH} cat /home/eccd/inventory", returnStdout: true).trim()
    echo "Updated inventory file contents:\n${inventory_file_output2}"
}

def systemctlParameterUpdateForWorkerNodes(parameterName, desiredParameterValue){
    echo "SNMP & UI WA"
    def parameter_check_command = "ansible worker -b -i inventory -m shell -a \"sysctl ${parameterName}\""
    def parameter_check = sh (script : "${directorNodeSSH} '${parameter_check_command}'", returnStdout: true).trim()
    echo "SYSCTL PARAMETERS BEFORE UPDATE:\n${parameter_check}"

    if (!parameter_check.contains("${parameterName} = ${desiredParameterValue}")){
        echo "UPDATING: ${parameterName} to ${desiredParameterValue} on all worker nodes"
        def parameter_update_command = "ansible worker -b -i inventory -m shell -a \"echo -e '${parameterName}=${desiredParameterValue}'>>/etc/sysctl.conf && sysctl -p\""
        def parameter_update = sh (script : "${directorNodeSSH} '${parameter_update_command}'", returnStdout: true).trim()

        //Checking if the parameters have been applied
        def parameter_check2 = sh (script : "${directorNodeSSH} '${parameter_check_command} | grep -v \'CHANGED\''", returnStdout: true).trim()
        def parametersAfterUpdate = parameter_check2.split('\n')

        for (String parameter : parametersAfterUpdate){
            if (!parameter.contains("${parameterName} = ${desiredParameterValue}")){
                unstable("The systemctl parameter '${parameterName}' could not be updated to the desired value '${desiredParameterValue}' on a worker node of the cluster, please check!")
                sh "echo 'WARNING: The systemctl parameter ${parameterName} could not be updated to the desired value ${desiredParameterValue} on a worker node of the cluster, please check!' >> ${DIT_deployment_name}_WARNINGS.txt"
            }
        }
        //print output of 2nd check to report file
        printToElementalsReport("'${parameterName}' sysctl Parameter check",parameter_check2)
    }
    else{
        echo "${parameterName} is already set to ${desiredParameterValue} on all worker nodes"
        printToElementalsReport("'${parameterName}' sysctl Parameter check",parameter_check)
    }
}

def CCD_loadBalancerServiceCheck(){
    //CCD Load Balancer (ingress-nginx) Service check
    def loadBalancerSVC_CCD = sh (script : "${kubectl} get svc -n ingress-nginx | grep ingress-nginx", returnStdout: true).trim()
    //sh "echo \"\nCCD Load Balancer (ingress-nginx) Service check:\n ${loadBalancerSVC_CCD}:\" >> ${DIT_deployment_name}_JIRA.txt"
    printToElementalsReport("CCD Load Balancer (ingress-nginx) Service check",loadBalancerSVC_CCD)
}

def checkAPInslookup(){
    //nslookup of CCD API URL
    def CCD_API_URL = sh (script : "${kubectl} get ingress -n kube-system | grep kubernetes-api | awk '{print \$3}'", returnStdout: true).trim()
    def nslookup_CCD_API_URL = sh (script : "docker run --rm armdocker.rnd.ericsson.se/proj-enm/cenm-build-utilities:latest nslookup ${CCD_API_URL}", returnStdout: true).trim()
    //sh "echo \"\nnslookup of ${CCD_API_URL}:\n${nslookup_CCD_API_URL}\" >> ${DIT_deployment_name}_JIRA.txt"
    printToElementalsReport("nslookup of ${CCD_API_URL}",nslookup_CCD_API_URL)
}

def readExcludeCIDRs(){
    //Check to see if IPv4/IPv6 VIP IP's have been added to the excludeCIDRs inside the kube-proxy configmap on the kube-system namespace
    echo "kube-proxy configmap contents"
    def kube_proxy_excludeCIDRS_check = sh (script : "${kubectl} get configmap -n kube-system kube-proxy -o yaml", returnStdout: true).trim()
    //sh "echo \"\nexcludeCIDR IPv4/IPv6 IP's:\n ${kube_proxy_excludeCIDRS_check}:\n\" >> ${DIT_deployment_name}_JIRA.txt"
    printToElementalsReport("kube-proxy configmap check",kube_proxy_excludeCIDRS_check)

    cENM_VIP_ComparisonCheckOutput = ""
    cENM_VIPsList.each { vip ->
        try{
            def vipFormatted = InetAddress.getByName("${vip.value}").getHostAddress()
            vipFormatted = vipFormatted.replace(":0:",":").replace(":0:","::").replace(":0:",":")

            if(kube_proxy_excludeCIDRS_check.contains("${vip.value}") || kube_proxy_excludeCIDRS_check.contains("${vipFormatted}")){
                if("${vip.value}" == "null" && (vip.name.contains("ipv6") || vip.name.contains("IPv6"))){
                    unstable("WARNING: The cENM ${vip.name} VIP IP is set to null in DIT, this warning can be ignored for cENM SingleStack deployments, otherwise please check DIT configuration.")
                    sh "echo 'WARNING: The cENM ${vip.name} VIP IP is set to null in DIT, this warning can be ignored for cENM SingleStack deployments, otherwise please check DIT configuration.' >> ${DIT_deployment_name}_WARNINGS.txt"
                }
                else if ("${vip.value}" == "null"){
                    unstable("WARNING: The cENM ${vip.name} VIP IP is set to null in DIT, please check!")
                    sh "echo 'WARNING: The cENM ${vip.name} VIP IP is set to null in DIT, please check!' >> ${DIT_deployment_name}_WARNINGS.txt"
                }
                else{
                    cENM_VIP_ComparisonCheckOutput += "The cENM ${vip.name} VIP IP ${vip.value} (as per DIT) is present inside the kube-proxy configmap\n"
                }
            }
            else{
                cENM_VIP_ComparisonCheckOutput += "WARNING: The cENM ${vip.name} VIP IP ${vip.value} (as per DIT) is not present inside the kube-proxy configmap, please check!\n"
                sh "echo 'WARNING: The cENM ${vip.name} VIP IP ${vip.value} (as per DIT) is not present inside the kube-proxy configmap, please check!' >> ${DIT_deployment_name}_WARNINGS.txt"
                unstable("WARNING: The cENM ${vip.name} VIP IP ${vip.value} (as per DIT) is not present inside the kube-proxy configmap, please check!")
            }
        }
        catch (exception){
            echo "Could not format cENM IPv6 VIP, this message can be ignored for SingleStack clusters. (${exception})"
            unstable("WARNING: Could not format cENM IPv6 VIP, this message can be ignored for SingleStack clusters.")
        }
    }
    printToElementalsReport("excludeCIDRs comparison between DIT",cENM_VIP_ComparisonCheckOutput)
}

def readNodeInternalIPs(){
    //Checking to see if IPv4/IPv6 internal node IP's are present inside the kubelet configuration
    totalNodeCount = sh (script : "${kubectl} get nodes | wc -l", returnStdout: true).trim()
    output = ""
    for(int i=1;i<Integer.parseInt(totalNodeCount);i++){
        def nodeName = sh (script : "${kubectl} get nodes | grep -v STATUS | awk '(NR==$i)' | awk '{print \$1}'", returnStdout: true).trim()
        def workerNodeInternalIPOutput =  sh (script : "${kubectl} get node ${nodeName} -o go-template --template='{{range .status.addresses}}{{printf \"%s: %s\\n\" .type .address}}{{end}}'\n", returnStdout: true).trim()

        //sh "echo \"\nInternal node IP's present inside the kubelet configuration for worker node:\n${workerNodeInternalIPOutput}\n\" >> ${DIT_deployment_name}_JIRA.txt"
        output += "\nInternal node IP's present inside the kubelet configuration for worker node:\n${workerNodeInternalIPOutput}\n"
    }
    printToElementalsReport("Internal node IPs",output)
}


def enmLoadBalancerCheck(){
    def nslookup_enmHOST_URL = sh (script : "docker run --rm armdocker.rnd.ericsson.se/proj-enm/cenm-build-utilities:latest nslookup ${enmHostURL}", returnStdout: true).trim()

    if("${ip_version}" == "IPv4" || "${ip_version}" == "Dual"){
        if(nslookup_enmHOST_URL.contains("${LoadBalancerIPv4}")){
            printToElementalsReport("The IPv4 cENM LoadBalancer IP (${LoadBalancerIPv4}) in DIT matches with what has been configured on the DNS entry of ${enmHostURL}",nslookup_enmHOST_URL)
        }
        else{
            printToElementalsReport("WARNING: The IPv4 cENM LoadBalancer IP (${LoadBalancerIPv4}) in DIT does not match with what has been configured on the DNS entry of ${enmHostURL}",nslookup_enmHOST_URL)
            unstable("WARNING: The IPv4 cENM LoadBalancer IP (${LoadBalancerIPv4}) in DIT does not match with what has been configured on the DNS entry of ${enmHostURL}")
        }
    }

    if("${ip_version}" == "IPv6_EXT" || "${ip_version}" == "Dual"){
        //convert IPv6 IP onto a common format
        def LoadBalancerIPv6Formatted = InetAddress.getByName("${LoadBalancerIPv6}").getHostAddress()
        LoadBalancerIPv6Formatted = LoadBalancerIPv6Formatted.replace(":0:",":").replace(":0:","::").replace(":0:",":")

        if(nslookup_enmHOST_URL.contains("${LoadBalancerIPv6Formatted}")){
            printToElementalsReport("The IPv6 cENM LoadBalancer IP (${LoadBalancerIPv6}) in DIT matches with what has been configured on the DNS entry of ${enmHostURL}",nslookup_enmHOST_URL)
        }
        else{
            printToElementalsReport("WARNING: The IPv6 cENM LoadBalancer IP (${LoadBalancerIPv6}) in DIT does not match with what has been configured on the DNS entry of ${enmHostURL}",nslookup_enmHOST_URL)
            unstable("WARNING: The IPv6 cENM LoadBalancer IP (${LoadBalancerIPv6}) in DIT does not match with what has been configured on the DNS entry of ${enmHostURL}")
        }
    }
}


def readECFE(){
    def metallbconfig_check = sh (script : "${kubectl} get configmap -n kube-system metallb-config -o yaml", returnStdout: true).trim()
    printToElementalsReport("ECFE Pool ",metallbconfig_check)
}

def readECFE_OpenStack(){
    def ecfe_ccdadm_check = sh (script : "${kubectl} get configmap -n kube-system ecfe-ccdadm -o yaml", returnStdout: true).trim()
    //printToElementalsReport("ECFE Pool ","${ecfe_ccdadm_check}")
}

def deployNFS(){
    def nfsChartStatus
    def nfsPodStatus
    def storage_class_check
    //check if NFS chart is already deployed
    try{
        nfsChartStatus = sh (script : "${helm} ls -n kube-system | grep ${rwx_storage_class_name}", returnStdout: true).trim()
        nfsPodStatus = sh (script : "${kubectl} get pods -n kube-system | grep ${rwx_storage_class_name}", returnStdout: true).trim()
        storage_class_check = sh (script : "${kubectl} get sc | grep ${rwx_storage_class_name}", returnStdout: true).trim()
    }
    catch (Exception e){
        if(e.toString().contains("hudson.AbortException: script returned exit code 1")){
            echo "NFS chart is not deployed..."

            nfsChartStatus = ""
            nfsPodStatus = ""
            storage_class_check = ""
        }
        else{
            throw e
        }
    }

    boolean isNfsChartDeployed = nfsChartStatus.contains("deployed")
    boolean isNfsPodRunning = nfsPodStatus.contains("Running")
    boolean isNfsPodContainerStarted = nfsPodStatus.contains("1/1")

    //set the NFS folder name variable
    nfsShareFolderName = env.enmNamespaceName.replace("enm", "nfs")
    echo "NFS share folder name to be used: ${nfsShareFolderName}"

    if (isNfsChartDeployed && isNfsPodRunning && isNfsPodContainerStarted){
        printToElementalsReport("NFS chart is deployed, NFS pod is in Running status:","NFS Chart:\n${nfsChartStatus}\n\nNFS Pod:\n${nfsPodStatus}\n\nNFS Storage Class:\n${storage_class_check}")
    }
    else if(!isNfsChartDeployed && !isNfsPodRunning && !isNfsPodContainerStarted){
        echo "NFS Chart/Pod needs to be deployed, configuring..."

        try{
            //Perform NFS VM configuration
            sh "python cENM_FlexiKube_OpenStack_UAT/NFS_VM_configuration.py '${rwx_storage_class_name}' '${nfs_ip}' '${nfsShareFolderName}'"

            //add nfs repo
            def nfs_repo_add_command = "helm repo add nfs-subdir-external-provisioner https://kubernetes-sigs.github.io/nfs-subdir-external-provisioner/"
            def nfs_repo_add = sh (script : "${directorNodeSSH} '${nfs_repo_add_command}'", returnStdout: true).trim()
            //def nfs_repo_add = sh (script : "${helm} repo add nfs-subdir-external-provisioner https://kubernetes-sigs.github.io/nfs-subdir-external-provisioner/", returnStdout: true).trim()
            echo "${nfs_repo_add}"


            def nfs_chart_deployment_command = "helm install ${rwx_storage_class_name} nfs-subdir-external-provisioner/nfs-subdir-external-provisioner --set storageClass.name=${rwx_storage_class_name} --set nfs.server=${nfs_ip} --set nfs.path=/share/ericsson/${nfsShareFolderName} --set storageClass.archiveOnDelete=false --namespace kube-system"
            def nfs_chart_deployment = sh (script : "${directorNodeSSH} '${nfs_chart_deployment_command}'", returnStdout: true).trim()
            //def nfs_chart_deployment = sh (script : "${helm} install ${rwx_storage_class_name} nfs-subdir-external-provisioner/nfs-subdir-external-provisioner --set storageClass.name=${rwx_storage_class_name} --set nfs.server=${nfs_ip} --set nfs.path=/share/ericsson/${nfsShareFolderName} --set storageClass.archiveOnDelete=false --namespace kube-system", returnStdout: true).trim()
            echo "${nfs_chart_deployment}"

            //sleeping for 60 seconds so nfs pod has time to come into Running status
            sleep(60)

            //Check NFS Chart/pod status again
            def nfsChartStatus2 = sh (script : "${helm} ls -n kube-system | grep ${rwx_storage_class_name}", returnStdout: true).trim()
            def nfsPodStatus2 = sh (script : "${kubectl} get pods -n kube-system | grep ${rwx_storage_class_name}", returnStdout: true).trim()
            def storage_class_check2 = sh (script : "${kubectl} get sc | grep ${rwx_storage_class_name}", returnStdout: true).trim()
            isNfsChartDeployed = nfsChartStatus2.contains("deployed")
            isNfsPodRunning = nfsPodStatus2.contains("Running")
            isNfsPodContainerStarted = nfsPodStatus2.contains("1/1")

            if (isNfsChartDeployed && isNfsPodRunning && isNfsPodContainerStarted){
                printToElementalsReport("NFS chart is deployed, NFS pod is in Running status:","NFS Chart:\n${nfsChartStatus2}\n\nNFS Pod:\n${nfsPodStatus2}\n\nNFS Storage Class:\n${storage_class_check2}")
            }
            else
            {
                error("An error has occurred during the NFS configuration")
            }
        }
        catch (exception){
            error("An error has occurred during the NFS configuration\nError: ${exception}\n")
        }
    }
    else if(isNfsChartDeployed && !isNfsPodRunning || !isNfsPodContainerStarted){
        echo "NFS chart is deployed however the pod is not in Running status or the pod container is not in 1/1 status, please check the NFS configuration manually!"
        sh "echo 'WARNING: NFS chart is deployed however the pod is not in Running status or the pod container is not in 1/1 status, please check the NFS configuration manually!' >> ${DIT_deployment_name}_WARNINGS.txt"
        unstable("WARNING: NFS chart is deployed however the pod is not in Running status or the pod container is not in 1/1 status, please check the NFS configuration manually!")
    }
    else{
        error("An error has occurred during the NFS configuration, please check manually")
    }
}



def clusterNode() {

}
return this