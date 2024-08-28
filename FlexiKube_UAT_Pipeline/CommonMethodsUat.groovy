import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.yaml.*
import java.util.regex.Pattern

def extract_jq(){
    echo "Extracting the jq software"
    sh "tar -xvf software/jq-1.0.1.tar ; chmod +x ./jq"
}

def read_inventory_file() {
    echo "HYDRA NAME: ${environment_name}"
    echo "DIT NAME: ${DIT_deployment_name}"
    echo "CLUSTER TYPE: ${cluster_type}"
}

def printToElementalsReport(name,output){
    sh "echo \"\n${name}\n\n{noformat}\n${output}\n{noformat}\n\" >> ${environment_name}_JIRA.txt"
}

def readHydraInformation() {
    productionToken = "d258d9d946c771448b8de590f735b90e309b56a9"
    hydra = "curl -k -s -X GET -H 'Authorization: ${productionToken}'"
    custom_field_id_directorCPU = "14506"
    getDirectorCPU = "https://hydra.gic.ericsson.se/api/8.0/instance_custom_data?instance_id=418433\\&custom_field_id=14506"


    // Get Instance ID from cluster name
    instanceID = sh (script : "${hydra} https://hydra.gic.ericsson.se/api/8.0/instance?name=${environment_name} | ./jq '.result[0].id'",returnStdout: true).trim()
    //echo "Instance ID  = ${instanceID}"

    directorCPU = sh (script : "curl -k -s -X GET -H 'Authorization: ${productionToken}' ${getDirectorCPU} | ./jq '.result[0].data'",returnStdout: true).trim()
    //echo "Director CPU Total = ${directorCPU}"

    def CustomFieldIds = ["OwnerSignum" : ["12978",""],
                          "NumberOfWorkers" : ["12984",""],
                          "WorkerCPU" : ["12985",""],
                          "WorkerMemory" : ["12986",""],
                          "WorkerDiskSize" : ["12987",""],
                          "WorkersNodes" : ["14273",""],
                          "DirectorCPU" : ["14506",""],
                          "DirectorMemory" : ["14507",""],
                          "DirectorDiskSize" : ["14508",""],
                          "MasterCPU" : ["18693",""],
                          "MasterMemory" : ["18694",""],
                          "MasterDiscSize" : ["18695",""],
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
    env.directorIP = "${directorNodeIP[0].dir_oam_ip}"
}

def kubectlSyntax() {
    env.kubeConfig = "${workspace}/kubeconfig/config"
    env.kubectl = "docker run --rm  -v ${kubeConfig}:/root/.kube/config -v ${WORKSPACE}:${WORKSPACE} --workdir ${WORKSPACE} ${cenm_utilities_docker_image} kubectl"
    env.helm = "docker run --rm -v ${kubeConfig}:/root/.kube/config -v ${WORKSPACE}:${WORKSPACE} --workdir ${WORKSPACE} ${cenm_utilities_docker_image} helm"
}

def copyKubeConfigFile() {
    sh "mkdir -p ${WORKSPACE}/kubeconfig/"

    //SCP the Kubeconfig file from the director node of the cluster onto the workspace directory of the Jenkins slave using directorNodeIP and clusterOnwerSignum obtained from HYDRA
    sh "sshpass -p ${clusterOwnerSignum} scp -o 'StrictHostKeyChecking=no' ${clusterOwnerSignum}@${directorIP}:/home/${clusterOwnerSignum}/.kube/config ${WORKSPACE}/kubeconfig/config"

    //sh "scp /tmp/UAT/flex9089 ${WORKSPACE}/kubeconfig/config"
}

def readDitInformation() {
    ditDeploymentUrlName = "https://atvdit.athtem.eei.ericsson.se/api/deployments?q=name%3D${DIT_deployment_name}"
    ditDocumentHead = 'https://atvdit.athtem.eei.ericsson.se/api/documents/'

    try{
        deploymentValue = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0].documents'",returnStdout: true).trim()
        echo "${deploymentValue}"

        def deploymentValues = readJSON text: deploymentValue

        //find the ID of the site_information document
        for (document in deploymentValues){
            if (document.schema_name == "cENM_site_information") {
                env.siteInformationDocumentID = document.document_id
                echo "site_information document ID is  : ${siteInformationDocumentID}"
            }
            if (document.schema_name == "cENM_integration_values") {
                env.integrationValueFileDocumentID = document.document_id
                echo "integration_value_file document ID is  : ${integrationValueFileDocumentID}"
            }
        }
    }
    catch (exception)
    {
        echo "ERROR:\n${exception}"
    }

    //extract the enm namespace name from the site_information document
    try{
        def site_information_document = sh (script : "curl -X GET https://atvdit.athtem.eei.ericsson.se/api/documents/${siteInformationDocumentID}",returnStdout: true).trim()
        def site_information_document_JSON = new JsonSlurper().parseText(site_information_document)
        env.enmNamespaceName = site_information_document_JSON.content.global.namespace

        //echo "site_information contents:\n${site_information_document}"
        echo "The ENM namespace name (as per DIT) is:\n${enmNamespaceName}"
    }
    catch (exception){
        echo "ERROR:\n${exception}"
    }

    //extract the enmHOST URL and the cENM LoadBalacer IPv4 & IPv6 IPs from the integration_values_file document
    try{
        def integration_value_file_document = sh (script : "curl -X GET https://atvdit.athtem.eei.ericsson.se/api/documents/${integrationValueFileDocumentID}",returnStdout: true).trim()
        def integration_value_file_document_JSON = new JsonSlurper().parseText(integration_value_file_document)

        //echo "integration_value_file contents:\n${integration_value_file_document}"

        env.enmHostURL = integration_value_file_document_JSON.content.global.ingress.enmHost
        echo "The enmHost URL (as per DIT) is: ${enmHostURL}\n"

        env.LoadBalancerIPv4 = integration_value_file_document_JSON.content."eric-oss-ingress-controller-nx".service.loadBalancerIP
        env.LoadBalancerIPv6 = integration_value_file_document_JSON.content."eric-oss-ingress-controller-nx".service.loadBalancerIP_IPv6
        echo "The cENM LoadBalancer IPv4 IP (as per DIT) is: ${LoadBalancerIPv4}\n"
        echo "The cENM LoadBalancer IPv6 IP (as per DIT) is: ${LoadBalancerIPv6}\n"
    }
    catch (exception){
        echo "ERROR:\n${exception}"
    }

}

def readClusterDimensionsData() {
    env.dimension_details = sh (script : "cat ${env.WORKSPACE}/FlexiKube_UAT_Pipeline/dimension_details.json", returnStdout: true).trim()
    def dimension_details_JSON = new JsonSlurper().parseText(dimension_details)
    echo "Cluster Dimensions details: ${dimension_details}"
    echo "Cluster Dimensions details of Orderable Item '${cluster_type}': ${dimension_details_JSON["${cluster_type}"]}"


    //extract expected master node details from json based on the cluster_type (Orderable Item)
    env.expected_master_node_count = "${dimension_details_JSON["${cluster_type}"].master.node_count}"
    env.expected_master_node_CPU = "${dimension_details_JSON["${cluster_type}"].master.cpu}"
    env.expected_master_node_RAM = "${dimension_details_JSON["${cluster_type}"].master.memory}"
    env.expected_master_node_storage = "${dimension_details_JSON["${cluster_type}"].master.volume}"

    //extract expected worker node details from json based on the cluster_type (Orderable Item)
    env.expected_worker_node_count = "${dimension_details_JSON["${cluster_type}"].worker.node_count}"
    env.expected_worker_node_CPU = "${dimension_details_JSON["${cluster_type}"].worker.cpu}"
    env.expected_worker_node_RAM = "${dimension_details_JSON["${cluster_type}"].worker.memory}"
    env.expected_worker_node_storage = "${dimension_details_JSON["${cluster_type}"].worker.volume}"

    //extract expected director node details from json based on the cluster_type (Orderable Item)
    env.expected_director_node_count = "${dimension_details_JSON["${cluster_type}"].director.node_count}"
    env.expected_director_node_CPU = "${dimension_details_JSON["${cluster_type}"].director.cpu}"
    env.expected_director_node_RAM = "${dimension_details_JSON["${cluster_type}"].director.memory}"
    env.expected_director_node_storage = "${dimension_details_JSON["${cluster_type}"].director.volume}"

    /*
    echo "Cluster is expected to be with the following resources:"
    echo "Expected Master Node Count: ${expected_master_node_count}"
    echo "Expected Master Node CPU: ${expected_master_node_CPU}"
    echo "Expected Master Node RAM: ${expected_master_node_RAM}"
    echo "Expected Master Node Volume Storage: ${expected_master_node_storage}"

    echo "Expected Worker Node Count: ${expected_worker_node_count}"
    echo "Expected Worker Node CPU: ${expected_worker_node_CPU}"
    echo "Expected Worker Node RAM: ${expected_worker_node_RAM}"
    echo "Expected Worker Node Volume Storage: ${expected_worker_node_storage}"

    echo "Expected Director Node Count: ${expected_director_node_count}"
    echo "Expected Director Node CPU: ${expected_director_node_CPU}"
    echo "Expected Director Node RAM: ${expected_director_node_RAM}"
    echo "Expected Director Node Volume Storage: ${expected_director_node_storage}"
    */
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
        //sh "echo ${hostsName} >> ${environment_name}_JIRA.txt"
        //sh "${kubectl} describe node ${hostsName} | grep 'ccd/version' >> ${environment_name}_JIRA.txt"
        sh "${kubectl} describe node ${hostsName} | grep 'ccd/version'"
        def ccdVersion = sh (script : "${kubectl} describe node ${hostsName} | grep 'ccd/version'", returnStdout: true).trim()
        output += "\n${hostsName}\n${ccdVersion}\n"
    }
    printToElementalsReport("CCD Version Check",output)
}

def uploadPemFileDit(){
    deploymentHeadId = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0]'",returnStdout: true).trim()
    def myHeads = readJSON text: deploymentHeadId
    env.myHead = myHeads._id
    pemUrl = "https://atvdit.athtem.eei.ericsson.se/api/deployments/${myHead}"

    def testPem = sh (script : "curl -k -s -X GET ${pemUrl}",returnStdout: true).trim()
    def myGetPem = readJSON text: testPem
    contentName = myGetPem.name
    contentProjectId = myGetPem.project_id
    contentSedId = myGetPem.enm.sed_id
    contentDummyKey = "-----BEGIN RSA PRIVATE KEY-----\r\nMIIEogIBAAKCAQEAutlfWK9+qBv+Msd3gbWDuScodoW1LExl2eOjSHRv/AnL+ucd\r\nHNRTwjOT5VzrEC9jf2COL5eu2t2gJCDkyDz59gJTv0o+XSH2p9LljbY7SEMfSK7c\r\nJ2PrQJbVdxKYcBErzGb8qf41+jG1P/fmD3jTeSA8hr6Dfe10BUsE+24EL9vNJW5j\r\n7dTDue+XlF3cf+hBTKMMy+U40CRFz5K8le6rV82VPnH0hoIhT72u/8vi30oqEzXG\r\n5x/OM/I2X6V0q+lvPvJkjUqktZn67PvhU+wcLjKmxxUrXFzz/dqeBIaPPMi8IAPk\r\nw/EUK031RxTQizVhwQAvbI1TzoyVKftlRvHeOwIDAQABAoIBAFAPbdRBNgLwI6Y8\r\nY493aB6AkczfE7cMcSPAbylPguA6jmVOe+HrdIwkr306qBnCRF7Cz4nC85AiIEj6\r\nsyy9O9lWO+4d8MTVFavpKKTk7VfUMuZgzkIuhRGiz4p6tEhogxzND/wCybwPansj\r\nTDda7TncPzL5FLxzbyAJefQFutOKHgdhjVgH7Um2NxGrQ6FoUSVajyoisAv+Yvvd\r\nhRfHniWI+3olprCCfDV7Vil4xSesVvFEofgsbGPZ4b3FMMkNqRWoLQDhyIKL6Nhr\r\ngORM+fG8UM5goJ9ZvTi6B2U3TruZ4qZc6I7D65/BlmFmm8wgMZ5alcs3KdQigdy6\r\npzMrTvkCgYEA5/2C+YD7gvHgV+Tbg2mz5o+Z1Z2hUAaOSn82xAyFRa5nleEDYEhH\r\nJb4dLOavOPqmmRM7ItDZENlxV2oSSoAVGptdVBgXi6GFkI6fDf1AeOap+yLrNAd2\r\nA50kiHR75vtv1fWwJnOb/abhknwXue6aB9EIRVdjx65gAV+txUzu9cUCgYEAzi/d\r\nJqOJNRjyD04yYB5YlAwydoGxCoV2qrHM/1W9+wQD3hkFS1ZL3CBb2u5NNAWlLKY0\r\nLEVVAHdyHXS5HJEzXmQHZa/yu2T5wV0885OD3EA/V3Zi2AjzClsC3LXEsqPIC4No\r\nh5vXAOCwfBwIxhtDIihhHgVmePEe8rx8r1I+w/8CgYAOOuqxy0uiOJv+SDd+1BkI\r\n534UMFsYwY4w26TMWchDAfOwqeC/Iy/aDNNVUcElyZo2gYt7Ezx9YBknt4Xvs/OX\r\ncjhDVEb9dabvuw/el85AnEWI9hdfVaXTiuwWwq5m+L1fbnajpSvIX1gu2BXMfepM\r\n2HGdb0LbmMKi0u+hzppJ0QKBgEyN7vGitJX3XiCaqw+PFNpbMP1ZJ++9IBM+ktuW\r\n7UPe+MSky5duQhpIFXLTGe0fz3UlfKeXUnkq4D7ZkMVvkAAS6cAytNApLKZDxRa3\r\nBbVoUVxbA1Ys9Hg61HQ4NQES2HqV3uDC1vBnfH+INSXBB4sOLQjlfmeXNyNvImhC\r\nBDXnAoGARQZ5PrNovdJYbBFGBzDOeVKvkrO++SYHFLlzpHADpkqY2uEQF2cQWDBu\r\nBvaQE7Yq19CDW2QgMwE7GTf1og2r8v0RgxOc5QeUdgNOADCuPNafvbyyzNzmgGjv\r\nPxY4r3DcvaJwW/TjwiIiyPkc6OALcUHLgGO7R2B5y+41oytYADA=\r\n-----END RSA PRIVATE KEY-----"

    def content = """
    {
    "name": "${contentName}",
    "project_id": "${contentProjectId}",
    "enm": {
    "sed_id": "${contentSedId}",
    "private_key": "${contentDummyKey}",
    "public_key": "${contentDummyKey}"
    }
    }"""
    writeFile file: 'pemconfig/json_put_pem', text: content
}

def updateDIT() {
    ditDeploymentUrlName = 'https://atvdit.athtem.eei.ericsson.se/api/deployments?q=name%3D{$DIT_deployment_name}'
    ditDocumentHead = 'https://atvdit.athtem.eei.ericsson.se/api/documents/'

    deploymentValue = sh (script : "curl -k -s -X GET ${ditDeploymentUrlName} | ./jq '.[0].documents'",returnStdout: true).trim()
    echo "${deploymentValue}"

    def deploymentValues = readJSON text: deploymentValue

    for (document in deploymentValues){
        if (document.schema_name == "cloud_native_enm_kube_config") {
            env.kubeConfigID = document.document_id
            echo "Kube config ID is  : ${kubeConfigID}"
        }
    }
    //gets template from dit, will need to update content of this
    def testConfig = sh (script : "curl -X GET https://atvdit.athtem.eei.ericsson.se/api/documents/${kubeConfigID}",returnStdout: true).trim()
    writeFile file: 'kubeconfig/json_get_config', text: testConfig

    //converts standard kube config file to json
    def inputFile = readYaml file: 'kubeconfig/config'
    def json = new JsonBuilder(inputFile).toPrettyString()
    writeFile file: 'kubeconfig/config', text: json

    //reads template into file variable
    jsonfile = readJSON file: 'kubeconfig/json_get_config'

    kubejsoncontent = readJSON file: 'kubeconfig/config'

    jsonfile['content'] = kubejsoncontent

    writeFile file: 'kubeconfig/json_get_config', text: jsonfile.toString()

}

def clusterSoftwareCheck() {

    def kubectlVersion = sh (script : "${directorNodeSSH} kubectl version", returnStdout: true).trim()
    echo "${kubectlVersion}"
    //sh "echo '\n${kubectlVersion}\n' >> ${environment_name}_JIRA.txt"
    printToElementalsReport("Kubectl Version Check",kubectlVersion)

    def dockerVersion = sh (script : "${directorNodeSSH} sudo docker version", returnStdout: true).trim()
    echo "${dockerVersion}"
    //sh "echo '\n${dockerVersion}\n' >> ${environment_name}_JIRA.txt"
    printToElementalsReport("Docker Version Check",dockerVersion)

    def helmVersion = sh (script : "${directorNodeSSH} helm version", returnStdout: true).trim()
    echo "${helmVersion}"
    //sh "echo '\n${helmVersion}\n' >> ${environment_name}_JIRA.txt"
    printToElementalsReport("Helm Version Check",helmVersion)


}

def storageClassCheck(){
    //Check to see if the "csi-cephfs-sc" storage class exists
    def storageClass =  sh (script : "${kubectl} get sc", returnStdout: true).trim()
    boolean doesCephStorageClassExist = storageClass.contains("csi-cephfs-sc")

    if (doesCephStorageClassExist){
        echo "csi-cephfs-sc storage class is present on the cluster."
        printToElementalsReport("Storage Class Check",storageClass)
    }

    else{
        echo "csi-cephfs-sc storage class is not present on the cluster!"

        //output storage class check command to JIRA.txt
        //sh "echo '\n${storageClass}\n' >> ${environment_name}_JIRA.txt"
        printToElementalsReport("Storage Class Check",storageClass)
        return false
    }
}

def clusterNodesCheck(){
    //Check to see if all nodes are in Running status
    def nodesList =  sh (script : "${kubectl} get nodes", returnStdout: true).trim()
    def nodesListTest = "random string \n yes"
    def nodeListOutput
    boolean doesContainNotReadyNodes = nodesList.contains("NotReady")

    if (doesContainNotReadyNodes){
        echo "One or more nodes are in NotReady status!"
        printToElementalsReport("Cluster Nodes",nodesList)
        return false
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
        echo "One or multiple pods are not in fully Running/Completed status status, please find the respective pods below:"
        echo "${notRunningPods}"
        sh "echo \"${notRunningPods}\" >> ${environment_name}_NotRunningPods.txt"
        return false
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
        def ramConverted = Math.ceil((ram[0][1].replaceAll("Ki","") as Double) / 1024.0 / 1024.0)
        def volumeStorageConverted = Math.ceil((volume_storage[0][1].replaceAll("Ki","") as Double) / 1024.0 / 1024.0)

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
    //sh "echo \"Total Number of master nodes present in this cluster: ${totalMasterNodeCount}\" >> ${environment_name}_JIRA.txt"
    printToElementalsReport("Master Node Count",totalMasterNodeCount)

    totalWorkerNodeCount = sh (script : "${kubectl} get nodes | grep worker | wc -l", returnStdout: true).trim()
    echo "Total Number of worker nodes present in this cluster: ${totalWorkerNodeCount}"
    //sh "echo \"Total Number of worker nodes present in this cluster: ${totalWorkerNodeCount}\n\" >> ${environment_name}_JIRA.txt"
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
        //sh "echo '\n\nNode Name: ${allocatedNodeResources['name']}' >> ${environment_name}_JIRA.txt"
        output += "\n\nNode Name: ${allocatedNodeResources['name']}"
        //sh "echo '\nName: ${allocatedNodeResources['name']}\nCPU: ${allocatedNodeResources['cpu']}\nRAM: ${allocatedNodeResources['ram']}GB\nStorage: ${allocatedNodeResources['storage']}GB' >> ${environment_name}_JIRA.txt"

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


//Helper function that compares an expected cluster resource value to the actual resource value
def compareTwoResources(expected,actual,comparisonText){
    try{
        if(expected == actual){
            echo "${comparisonText} matches the orderable item!\nExpected value: ${expected}\nActual value: ${actual}"
            //sh "echo \"${comparisonText} matches the orderable item!\" >> ${environment_name}_JIRA.txt"
            return ["\n${comparisonText} matches the orderable item!", true]
            //sh "echo \"${comparisonText} matches the orderable item!\nExpected value: ${expected}\nActual value: ${actual}\" >> ${environment_name}_JIRA.txt"
        }
        else {
            echo "WARNING: ${comparisonText} does not match the orderable item!\nExpected value: ${expected}\nActual value: ${actual}"
            //sh "echo \"WARNING: ${comparisonText} does not match the orderable item!\nExpected value: ${expected}\nActual value: ${actual}\" >> ${environment_name}_JIRA.txt"
            return ["\nWARNING: ${comparisonText} does not match the orderable item!\nExpected value: ${expected}\nActual value: ${actual}", false]
        }
    }
    catch (exception){
        echo "\nError: ${exception}\n"
    }

}

def createNamespaces(){
    def checkNamespaces = sh (script : "${kubectl} get ns", returnStdout: true).trim()

    boolean doesContainNamespaceForCRD = checkNamespaces.contains("eric-crd-ns")
    boolean doesContainNamespaceForENM = checkNamespaces.contains("${enmNamespaceName}")

    if (doesContainNamespaceForCRD)
        echo "Namespace 'eric-crd-ns' already exists:\n${checkNamespaces}"
    else{
        def createNamespaceForCRD = sh (script : "${kubectl} create ns eric-crd-ns", returnStdout: true).trim()
        echo "${createNamespaceForCRD}"
    }

    if (doesContainNamespaceForENM)
        echo "Namespace '${enmNamespaceName}' already exists:\n${checkNamespaces}"
    else{
        def createNamespaceForENM = sh (script : "${kubectl} create ns ${enmNamespaceName}", returnStdout: true).trim()
        echo "${createNamespaceForENM}"
    }

    //output namespace check command to JIRA.txt
    //sh "${kubectl} get ns >> ${environment_name}_JIRA.txt"
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

def SNMP_and_UI_WA(){
    echo "SNMP & UI WA"
    def parameter_check = sh (script : "${directorNodeSSH} \"\"\"ansible worker -b -i inventory -m shell -a 'sysctl net.ipv4.conf.all.rp_filter;sysctl net.ipv4.conf.tunl0.rp_filter;sysctl net.ipv4.vs.conntrack'\"\"\"", returnStdout: true).trim()
    echo "${parameter_check}"

    //Checking the net.ipv4.conf.all.rp_filter, net.ipv4.conf.tunl0.rp_filter and net.ipv4.vs.conntrack parameter values
    def parameter = "ansible worker -b -i inventory -m shell -a \"sysctl net.ipv4.conf.all.rp_filter;sysctl net.ipv4.conf.tunl0.rp_filter;sysctl net.ipv4.vs.conntrack\""
    def parameter_check1 = sh (script : "${directorNodeSSH} '${parameter}'", returnStdout: true).trim()
    echo "${parameter_check1}"


    //Checks to see if parameter net.ipv4.conf.all.rp_filter is present, if not will add net.ipv4.conf.all.rp_filter=0, if parameter is present but not '0' it will update it to '0'
    def parameter_update_1_command = "ansible worker -b -i inventory -m shell -a \"grep -q -F 'net.ipv4.conf.all.rp_filter=' /etc/sysctl.conf && sed -i 's/net.ipv4.conf.all.rp_filter=.*/net.ipv4.conf.all.rp_filter=0/g' /etc/sysctl.conf || (echo 'net.ipv4.conf.all.rp_filter=0' >> /etc/sysctl.conf && sed -i '/^net.ipv4.conf.all.rp_filter=/d' /etc/sysctl.conf) && sysctl -p\""
    def parameter_update_1 = sh (script : "${directorNodeSSH} '${parameter_update_1_command}'", returnStdout: true).trim()
    echo "${parameter_update_1}"

    //Checks to see if parameter net.ipv4.conf.tunl0.rp_filter is present, if not will add net.ipv4.conf.tunl0.rp_filter=0, if parameter is present but not '0' it will update it to '0'
    def parameter_update_2_command = "ansible worker -b -i inventory -m shell -a \"grep -q -F 'net.ipv4.conf.tunl0.rp_filter=' /etc/sysctl.conf && sed -i 's/net.ipv4.conf.tunl0.rp_filter=.*/net.ipv4.conf.tunl0.rp_filter=0/g' /etc/sysctl.conf || (echo 'net.ipv4.conf.tunl0.rp_filter=0' >> /etc/sysctl.conf && sed -i '/^net.ipv4.conf.tunl0.rp_filter=/d' /etc/sysctl.conf) && sysctl -p\""
    def parameter_update_2 = sh (script : "${directorNodeSSH} '${parameter_update_2_command}'", returnStdout: true).trim()
    echo "${parameter_update_2}"

    //Checks to see if parameter net.ipv4.vs.conntrack is present, if not will add net.ipv4.vs.conntrack=1, if parameter is present but not '1' it will update it to '1'
    def parameter_update_3_command = "ansible worker -b -i inventory -m shell -a \"grep -q -F 'net.ipv4.vs.conntrack=' /etc/sysctl.conf && sed -i 's/net.ipv4.vs.conntrack=.*/net.ipv4.vs.conntrack=1/g' /etc/sysctl.conf || (echo 'net.ipv4.vs.conntrack=1' >> /etc/sysctl.conf && sed -i '/^net.ipv4.vs.conntrack=/d' /etc/sysctl.conf) && sysctl -p\""
    def parameter_update_3 = sh (script : "${directorNodeSSH} '${parameter_update_3_command}'", returnStdout: true).trim()
    echo "${parameter_update_3}"


    //Checking the net.ipv4.conf.all.rp_filter, net.ipv4.conf.tunl0.rp_filter and net.ipv4.vs.conntrack parameters and saving to file
    def parameter_check2 = sh (script : "${directorNodeSSH} \"\"\"ansible worker -b -i inventory -m shell -a 'sysctl net.ipv4.conf.all.rp_filter;sysctl net.ipv4.conf.tunl0.rp_filter;sysctl net.ipv4.vs.conntrack'\"\"\"", returnStdout: true).trim()
    //sh "echo '\n${parameter_check2}\n' >> ${environment_name}_JIRA.txt"
    printToElementalsReport("net.ipv4.conf.all.rp_filter, net.ipv4.conf.tunl0.rp_filter and net.ipv4.vs.conntrack sysctl Parameter check",parameter_check2)
}

def sysctlParameterUpdate2(){
    //TODO: Only execute on CCD version 2.21.0 or above (most likely not cluster UAT will be required below CCD version 2.21.0 so may not be required)

    echo "net.ipv4.vs.conn_reuse_mode and net.ipv4.vs.expire_nodest_conn parameter update"
    def parameter_check = sh (script : "${directorNodeSSH} \"\"\"ansible worker -b -i inventory -m shell -a 'sysctl net.ipv4.vs.conn_reuse_mode;sysctl net.ipv4.vs.expire_nodest_conn'\"\"\"", returnStdout: true).trim()
    echo "${parameter_check}"

    //Checks to see if parameter net.ipv4.vs.conn_reuse_mode is present, if not will add net.ipv4.vs.conn_reuse_mode=0, if parameter is present but not '0' it will update it to '0'
    def parameter_update_1_command = "ansible worker -b -i inventory -m shell -a \"grep -q -F 'net.ipv4.vs.conn_reuse_mode=' /etc/sysctl.conf && sed -i 's/net.ipv4.vs.conn_reuse_mode=.*/net.ipv4.vs.conn_reuse_mode=0/g' /etc/sysctl.conf || (echo 'net.ipv4.vs.conn_reuse_mode=0' >> /etc/sysctl.conf && sed -i '/^net.ipv4.vs.conn_reuse_mode=/d' /etc/sysctl.conf) && sysctl -p\""
    def parameter_update_1 = sh (script : "${directorNodeSSH} '${parameter_update_1_command}'", returnStdout: true).trim()
    echo "${parameter_update_1}"

    //Checks to see if parameter net.ipv4.vs.expire_nodest_conn is present, if not will add net.ipv4.vs.expire_nodest_conn=1, if parameter is present but not '1' it will update it to '1'
    def parameter_update_2_command = "ansible worker -b -i inventory -m shell -a \"grep -q -F 'net.ipv4.vs.expire_nodest_conn=' /etc/sysctl.conf && sed -i 's/net.ipv4.vs.expire_nodest_conn=.*/net.ipv4.vs.expire_nodest_conn=1/g' /etc/sysctl.conf || (echo 'net.ipv4.vs.expire_nodest_conn=1' >> /etc/sysctl.conf && sed -i '/^net.ipv4.vs.expire_nodest_conn=/d' /etc/sysctl.conf) && sysctl -p\""
    def parameter_update_2 = sh (script : "${directorNodeSSH} '${parameter_update_2_command}'", returnStdout: true).trim()
    echo "${parameter_update_2}"

    //Checking the net.ipv4.vs.conn_reuse_mode and net.ipv4.vs.expire_nodest_conn parameters and saving to file
    def parameter_check2 = sh (script : "${directorNodeSSH} \"\"\"ansible worker -b -i inventory -m shell -a 'sysctl net.ipv4.vs.conn_reuse_mode;sysctl net.ipv4.vs.expire_nodest_conn'\"\"\"", returnStdout: true).trim()
    //sh "echo '\n${parameter_check2}\n' >> ${environment_name}_JIRA.txt"
    printToElementalsReport("net.ipv4.vs.conn_reuse_mode and net.ipv4.vs.expire_nodest_conn sysctl Parameter check",parameter_check2)
}

def sysctlParameterUpdate3(){
    //TODO: Only execute on CCD version 2.21.0 or above (most likely not cluster UAT will be required below CCD version 2.21.0 so may not be required)

    echo "net.ipv4.vs.run_estimation sysctl.conf parameter update"
    def parameter_check = sh (script : "${directorNodeSSH} \"\"\"ansible worker -b -i inventory -m shell -a 'sysctl net.ipv4.vs.run_estimation'\"\"\"", returnStdout: true).trim()
    echo "${parameter_check}"

    //Checks to see if parameter net.ipv4.vs.run_estimation is present, if not will add net.ipv4.vs.run_estimation=1, if parameter is present but not '1' it will update it to '1'
    def parameter_update_command = "ansible worker -b -i inventory -m shell -a \"grep -q -F 'net.ipv4.vs.run_estimation=' /etc/sysctl.conf && sed -i 's/net.ipv4.vs.run_estimation=.*/net.ipv4.vs.run_estimation=1/g' /etc/sysctl.conf || (echo 'net.ipv4.vs.run_estimation=1' >> /etc/sysctl.conf && sed -i '/^net.ipv4.vs.run_estimation=/d' /etc/sysctl.conf) && sysctl -p\""
    def parameter_update = sh (script : "${directorNodeSSH} '${parameter_update_command}'", returnStdout: true).trim()
    echo "${parameter_update}"

    //Checking the net.ipv4.vs.run_estimation parameter and saving to file
    def parameter_check2 = sh (script : "${directorNodeSSH} \"\"\"ansible worker -b -i inventory -m shell -a 'sysctl net.ipv4.vs.run_estimation'\"\"\"", returnStdout: true).trim()
    //sh "echo '\n${parameter_check2}\n' >> ${environment_name}_JIRA.txt"
    printToElementalsReport("net.ipv4.vs.run_estimation sysctl Parameter check",parameter_check2)
}

def dualStackConfigurationCheck(){
    //sh "echo \"\nDualStack Checks:\n\" >> ${environment_name}_JIRA.txt"

    //CCD Load Balancer (ingress-nginx) Service check
    def loadBalancerSVC_CCD = sh (script : "${kubectl} get svc -n ingress-nginx | grep ingress-nginx", returnStdout: true).trim()
    //sh "echo \"\nCCD Load Balancer (ingress-nginx) Service check:\n ${loadBalancerSVC_CCD}:\" >> ${environment_name}_JIRA.txt"
    printToElementalsReport("CCD Load Balancer (ingress-nginx) Service check",loadBalancerSVC_CCD)


    //Check to see if IPv4/IPv6 VIP IP's have been added to the excludeCIDRs inside the kube-proxy configmap on the kube-system namespace
    def kube_proxy_excludeCIDRS_check = sh (script : "${kubectl} get configmap -n kube-system kube-proxy -o yaml", returnStdout: true).trim()
    //sh "echo \"\nexcludeCIDR IPv4/IPv6 IP's:\n ${kube_proxy_excludeCIDRS_check}:\n\" >> ${environment_name}_JIRA.txt"
    printToElementalsReport("excludeCIDRs check",kube_proxy_excludeCIDRS_check)


    //Checking to see if IPv4/IPv6 internal node IP's are present inside the kubelet configuration
    totalWorkerNodeCount = sh (script : "${kubectl} get nodes | grep worker | wc -l", returnStdout: true).trim()
    output = ""
    for(int i=1;i<Integer.parseInt(totalWorkerNodeCount);i++){
        def nodeName = sh (script : "${kubectl} get nodes | grep -v STATUS | awk '(NR==$i)' | awk '{print \$1}'", returnStdout: true).trim()
        def workerNodeInternalIPOutput =  sh (script : "${kubectl} get node ${nodeName} -o go-template --template='{{range .status.addresses}}{{printf \"%s: %s\\n\" .type .address}}{{end}}'\n", returnStdout: true).trim()

        //sh "echo \"\nInternal node IP's present inside the kubelet configuration for worker node:\n${workerNodeInternalIPOutput}\n\" >> ${environment_name}_JIRA.txt"
        output += "\nInternal node IP's present inside the kubelet configuration for worker node:\n${workerNodeInternalIPOutput}\n"
    }
    printToElementalsReport("Internal node IPs",output)

    //nslookup of CCD API URL
    def CCD_API_URL = sh (script : "${kubectl} get ingress -n kube-system | grep kubernetes-api | awk '{print \$3}'", returnStdout: true).trim()
    def nslookup_CCD_API_URL = sh (script : "nslookup ${CCD_API_URL}", returnStdout: true).trim()
    //sh "echo \"\nnslookup of ${CCD_API_URL}:\n${nslookup_CCD_API_URL}\" >> ${environment_name}_JIRA.txt"
    printToElementalsReport("nslookup of ${CCD_API_URL}",nslookup_CCD_API_URL)
}

def enmHostURLCheck(){
    def nslookup_enmHOST_URL = sh (script : "nslookup ${enmHostURL}", returnStdout: true).trim()
    //sh "echo \"\nnslookup of ${enmHostURL}:\n${nslookup_enmHOST_URL}\" >> ${environment_name}_JIRA.txt"
    printToElementalsReport("nslookup of ${enmHostURL}",nslookup_enmHOST_URL)

    //extract the IP's from the nslookup command
    def pattern = /(?:Address:\s+)?(\d+\.\d+\.\d+\.\d+|[\da-fA-F:]+)(?=\n|$)/
    def matches = nslookup_enmHOST_URL.toString() =~ pattern
    def addresses = matches.collect {it[1]}

    //check if the cENM IPv4/IPv6 LB IP's as mentioned in DIT are configured in the DNS
    def hasIPv4 = LoadBalancerIPv4 in addresses
    def hasIPv6 = LoadBalancerIPv6 in addresses

    if(hasIPv4 && hasIPv6){
        echo "The IPv4 cENM LoadBalancer IP (${LoadBalancerIPv4}) in DIT matches with what has been configured on the DNS entry of ${enmHostURL}"
        echo "The IPv6 cENM LoadBalancer IP (${LoadBalancerIPv6}) in DIT matches with what has been configured on the DNS entry of ${enmHostURL}"
    }
    else if (hasIPv4){
        echo "The IPv4 cENM LoadBalancer IP (${LoadBalancerIPv4}) in DIT matches with what has been configured on the DNS entry of ${enmHostURL}"
        echo "WARNING: The IPv6 cENM LoadBalancer IP (${LoadBalancerIPv6}) in DIT does not match with what has been configured on the DNS entry of ${enmHostURL}"
        return false
    }
    else if (hasIPv6){
        echo "The IPv6 cENM LoadBalancer IP (${LoadBalancerIPv6}) in DIT matches with what has been configured on the DNS entry of ${enmHostURL}"
        echo "WARNING: The IPv4 cENM LoadBalancer IP (${LoadBalancerIPv4}) in DIT does not match with what has been configured on the DNS entry of ${enmHostURL}"
        return false
    }
    else{
        echo "WARNING: The IPv4 cENM LoadBalancer IP (${LoadBalancerIPv4}) in DIT does not match with what has been configured on the DNS entry of ${enmHostURL}"
        echo "WARNING: The IPv6 cENM LoadBalancer IP (${LoadBalancerIPv6}) in DIT does not match with what has been configured on the DNS entry of ${enmHostURL}"
        return false
    }
}


def readECFE(){
    def metallbconfig_check = sh (script : "${kubectl} get configmap -n kube-system metallb-config -o yaml", returnStdout: true).trim()
    //sh "echo \"\nmetallb-config file contents:\n ${metallbconfig_check}:\n\" >> ${environment_name}_JIRA.txt"
    printToElementalsReport("ECFE Pool ",metallbconfig_check)
}

def clientMachineConfiguration(){
    //TODO: Verify Docker Registry login is working ok from the client machine
    //TODO: Verify Director node login is working ok from the client machine
    //TODO: Verify Kubectl commands are working ok from the client machine
    //TODO: Verify/Update Client Machine VM kubectl, docker and HELM software versions (if software version is below that of the director node of the respective cluster)
}

def clusterNode() {

}
return this