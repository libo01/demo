node {
    while (true) {
        try {
            def params = getSlackChannel()
            if (params[0] && params[1]) {
                println "Start deploying change set: " + params[1]
                try {
                    deploy(params[0], params[1], params[2])
                } catch(Exception e) {
                    println e
                }
            }
            else 
                println "No new deployment"
        } catch (Exception e) {
           println e
        }
        sleep(60)
    }
}

def String[] getSlackChannel() {
    String token = "xoxp-162799009137-163657709348-263204798724-b0ef4b8ebde2c423307d7e11a49dd1dd"
    String channel = "C6FK6PP3Q"
    String count = 10
    String historyFile = "../${workspace}@script/DeployHistory.txt"
    def deploys = [:]
    //A log file to save the processed depoloyments
    File file = new File(historyFile)
    def lines = file.readLines()
    lines.each{ String line ->
        if (line) {
            deploys.put(line, line)
        }
    }
    def jsonStr = new URL("https://slack.com/api/channels.history?token=" + token +"&channel=" + channel + "&count=" + count).text
    def slurp = new groovy.json.JsonSlurper().parseText(jsonStr)
    String[] dep = new String[3]
    slurp.each{key, val ->
        if (key == "messages") {
            //get slack messages in reverse order
            for (msg in val.reverse()) {
                //if this deploy is in the log file. If it is not in the file, continue to deploy
                if (msg.toString().indexOf("@deploy")>0 && (deploys.find{it.key == msg.toString()} == null)) {
                    for (def m in msg) {
                        if (m.getKey() == "text") {
                            //text command from slack
                            def t = m.getValue().split(" ")
                            if (t.length == 3) {
                                dep[0] = t[1]
                                dep[1] = t[2]
                            }
                        }
                        if (m.getKey() == "user") {
                            dep[2] = getUser(m.getValue())
                        }
                    }
                    //deploy
                    if (dep[0] && dep[1]) {
                        println "New Deployment:\nSandbox: " + dep[0] + ", Change Set: " + dep[1] + ", Deploy By: " + dep[2]
                        file.append(msg.toString() + "\r\n")
                        break
                    } else 
                        dep = new String[3]
                }
            }
        }
    }
    return dep
}

/*Get Slack User Real Name*/
def String getUser(userId) {
    String token = "xoxp-162799009137-163657709348-263204798724-b0ef4b8ebde2c423307d7e11a49dd1dd"
    String user = ""
    String jsonStr = new URL("https://slack.com/api/users.info?token=" + token + "&user=" + userId).text
    def slurp = new groovy.json.JsonSlurper().parseText(jsonStr)
    slurp.each{key, val ->
        if (key == "user") {
            for (msg in val) {
                if (msg.getKey() == "name" && user=="") {
                    user = msg.getValue()
                }
                if (msg.getKey() == "real_name") {
                    user = msg.getValue()
                }
            }
        }
    }
    return user
}

def  getDeploy() {
    def file = new File("DeployHistory.txt")
    if (file.exists()) {
       
        file.eachLine{ String line ->
            
        }
    }
}

def deploy(String sourceEvn, String changeSet, String user) {
    String buildStatus = "warning"
    def envs = "${Deploy_Env}".split(',')
    Integer numEnvs = envs.length
    Integer count = 0
    if (!sourceEvn)
        sourceEvn = "${Retrieve_Env}"
    if (!changeSet)
        changeSet = "${Change_Set}"
    String results = "Salesforce Sandboxes Deployments\nChange Set: " + changeSet + "\nDeployed By: " + user + "\n"
    try {
        sendSlackNotice("good", results + "Deployments start\n")
        stage "Retrieve"
        try {
            println "Retrieve change set " + changeSet + " from sandbox " + sourceEvn
            def retrieve = build job: 'SFDC_Deploy_Retrieve', parameters: [string(name: 'Rtrieve_Env', value: sourceEvn), 
                            string(name: 'Change_Set', value: changeSet)]
            def retrieveResult = retrieve.getResult()
            if (retrieveResult == 'SUCCESS') {
                file = readFile("${JENKINS_HOME}/jobs/SFDC_Deploy_Retrieve/workspace/ChangeSet/build_properties.txt")
                prop=[:]
                file.eachLine{ String line ->
                    l=line.split("=")
                    prop[l[0]]=l[1]
                }
                def changeSetFile = prop["changeSetFile"] + ".zip"
                println "Change set zip file: ${changeSetFile}"
                results += "Deploy Results:\n"
                for (i = 0; i < numEnvs; i++) {
                    println "Deploying to ${env}"
                    def env = envs[i].trim()
                    stage env
                    try {
                        if (!user)
                            user = "${Deploy_By}"
                        def deploy = build job: 'SFDC_Deploy_Admin', parameters: [string(name: 'APPLICATION', value: 'Lightning'), 
                                    text(name: 'VERSION', value: '1.0'), string(name: 'DEPLOY_ENV', value: "${env}"), string(name: 'DEPLOY_TESTLEVEL', value: 'NoTestRun'), 
                                    booleanParam(name: 'DEPLOY_CHECKONLY', value: false), string(name: 'Change_Set', value: "${changeSetFile}"), 
                                    string(name: 'Deployed_By', value: user)]
                        def deployResult = deploy.getResult()
                        println "Deploy Result: ${env} ${deployResult}"
                        results += "${env}: ${deployResult}\n"
                        if (deployResult == 'SUCCESS') {
                            count++
                            if (buildStatus == "warning")
                                buildStatus = "good"
                        } else if (deployResult != 'SUCCESS' && buildStatus != "danger") {
                            buildStatus = "danger"
                        }
                    }
                    catch (Exception e) {
                        println "Exception build:" + e            
                    }
                }
            }
        } catch (Exception e) {
            println "Exception build: " + e    
        } 
    } catch(Exception e) {
        println "Exception: " + e
    } finally {
        results += "Number of sandboxes to deploy: ${numEnvs}\nSucceed to deploy: ${count}"
        sendSlackNotice("${buildStatus}", "${results}")    
    }    
}

def sendSlackNotice(String color, String message) {
    String slackChannel = "demo"
    String slackToken = "CxHiU57jSVFvJaFF6KGtLvkS"
    slackSend channel: slackChannel, color: color, message: message, token: slackToken
}