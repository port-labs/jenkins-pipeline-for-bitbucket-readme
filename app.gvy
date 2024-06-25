import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.net.URLEncoder
import java.util.HashMap
import groovy.json.JsonSlurperClassic


pipeline {
    agent any

    stages {
        stage('Get Port Access Token') {
            steps {
                script {
                    withCredentials([
                        string(credentialsId: 'PORT_CLIENT_ID', variable: 'PORT_CLIENT_ID'),
                        string(credentialsId: 'PORT_CLIENT_SECRET', variable: 'PORT_CLIENT_SECRET')
                    ]) {
                        // Execute the curl command and capture the output
                        def result = sh(returnStdout: true, script: """
                            accessTokenPayload=\$(curl -X POST \
                                -H "Content-Type: application/json" \
                                -d '{"clientId": "${PORT_CLIENT_ID}", "clientSecret": "${PORT_CLIENT_SECRET}"}' \
                                -s "https://api.getport.io/v1/auth/access_token")
                            echo \$accessTokenPayload
                        """)

                        // Parse the JSON response using JsonSlurper
                        def jsonSlurper = new JsonSlurper()
                        def payloadJson = jsonSlurper.parseText(result.trim())

                        env.PORT_ACCESS_TOKEN = payloadJson.accessToken
                    }

                }
            }
        } // end of stage Get access token
        
        stage('Fetch Bitbucket Projects') {
            steps {
                withCredentials([
                    string(credentialsId: 'BITBUCKET_USERNAME', variable: 'BITBUCKET_USERNAME'),
                    string(credentialsId: 'BITBUCKET_APP_PASSWORD', variable: 'BITBUCKET_APP_PASSWORD'),
                    string(credentialsId: 'BITBUCKET_HOST', variable: 'BITBUCKET_HOST')
                ]) {
                    script {
                        def url = "${env.BITBUCKET_HOST}/rest/api/1.0/projects"
                        def allProjects = fetchPaginatedData(url)
                        
                        allProjects.each { project ->
                            def entity = [
                                identifier: project.key,
                                title: project.name,
                                properties: [
                                    description: project.get("description"),
                                    type: project.type,
                                    public: project.public,
                                    link: project.links.self[0].href
                                ]
                            ]
                        echo "Upserting project: ${entity.identifier}"
                        
                        addEntityToPort("bitbucketProject", entity)
                        
                        }
                        env.PROJECTS_JSON = JsonOutput.toJson(allProjects)
                    
                    }
                }
            }
        } // end of stage Fetch Bitbucket Projects

        stage('Fetch Repositories') {
            steps {
                withCredentials([
                    string(credentialsId: 'BITBUCKET_USERNAME', variable: 'BITBUCKET_USERNAME'),
                    string(credentialsId: 'BITBUCKET_APP_PASSWORD', variable: 'BITBUCKET_APP_PASSWORD'),
                    string(credentialsId: 'BITBUCKET_HOST', variable: 'BITBUCKET_HOST')
                ]) {
                    script {
                        def projects = env.PROJECTS_JSON

                        def jsonResponse = jsonParser(projects)
                        
                        def allRepoEntities = []
                        
                        jsonResponse.each { project ->
                            try {
                                def projectKey = project.key
                                println("Fetching repos for Project Key: ${projectKey}")
                                def repoUrl = "${env.BITBUCKET_HOST}/rest/api/1.0/projects/${projectKey}/repos"
                                def repos = fetchPaginatedData(repoUrl)
                                
                                repos.each { repo ->
                                    def repoReadmeUrl = "${env.BITBUCKET_HOST}/rest/api/1.0/projects/${projectKey}/repos/${repo.slug}/browse/README.md"
                                    def readmeResponse = fetchPaginatedData(repoReadmeUrl, 500, 'lines')
                                    
                                    def parsedReadme = parseRepositoryFileResponse(readmeResponse)
                                    
                                    def entity = [
                                        identifier: repo.slug,
                                        title: repo.name,
                                        properties: [
                                            description: repo.get("description"),
                                            state: repo.state,
                                            forkable: repo.forkable,
                                            public: repo.public,
                                            link: repo.links.self[0].href,
                                            documentation: parsedReadme,
                                            swagger_url: "https://api.${repo.slug}.com"
                                        ],
                                        relations: [project: repo.project.key]
                                    ]
                                    
                                    allRepoEntities << entity
                                    
                                }
                            } catch (Exception e) {
                                println("Failed to process project: ${projectKey}")
                                println("Error: ${e}")
                            }
                        }

                        env.REPOSITORIES_JSON = JsonOutput.toJson(allRepoEntities)
                    }
                }
            }
        } // end of stage Fetch Repositories

        stage('Process and Send Repositories to Port') {
            steps {
                script {
                    def repoEntities = jsonParser(env.REPOSITORIES_JSON)
                    echo "Processing ${repoEntities.size()} repos."

                    repoEntities.each { entity ->
                        echo "Processing repo: ${entity.identifier}"
                        
                        addEntityToPort("bitbucketRepository", entity)
                    }
                }
            }
        } // end of stage Process and Send Repositories to Port
    }

    post {
        always {
            cleanWs()
        }
    }
}

@NonCPS
def jsonParser(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

def parseRepositoryFileResponse(def lines) {
    def readmeContent = ""

    lines.each { line ->
        readmeContent += line.get("text", "") + "\n"
    }

    return readmeContent
}

def addEntityToPort(String blueprintId, Map entityObject) {
    def portApiUrl = 'https://api.getport.io/v1'
    def portHeaders = [
        'Content-Type': 'application/json',
        'Authorization': "Bearer ${env.PORT_ACCESS_TOKEN}"
    ]

    def headersString = portHeaders.collect { k, v -> "-H \"${k}: ${v}\"" }.join(' ')
    def entityJson = new groovy.json.JsonBuilder(entityObject).toString()

    def curlCommand = """
    curl -s -X POST '${portApiUrl}/blueprints/${blueprintId}/entities?upsert=true&merge=true' \
    ${headersString} \
    -d '${entityJson}'
    """

    echo "Running command: ${curlCommand}"

    def response = sh(returnStdout: true, script: curlCommand)
    def jsonResponse = new groovy.json.JsonSlurperClassic().parseText(response)
    echo jsonResponse.toString()
}

def fetchPaginatedData(url, def limit=25, def dataKey='values') {
    def allData = []
    def nextPageStart = null

    while (true) {
        try{
            def params = nextPageStart ? ["start": nextPageStart, "limit": limit] : ["limit": limit]
            def paramsString = params.collect { k, v -> "${k}=${v}" }.join("&")
            def curlCommand = """
            curl -s -X GET '${url}?${paramsString}' --user '${env.BITBUCKET_USERNAME}:${env.BITBUCKET_APP_PASSWORD}'
            """
            echo "Running command: ${curlCommand}"
    
            def response = sh(returnStdout: true, script: curlCommand)    
            def jsonResponse = jsonParser(response)
    
            if (jsonResponse.containsKey(dataKey)) {
                if (jsonResponse[dataKey] instanceof List) {
                    jsonResponse[dataKey].each { entry ->
                        try {
                            allData << new HashMap(entry)
                        } catch (Exception e) {
                            println("Failed to add entry: ${entry}")
                            println("Error: ${e}")
                        }
                    }
                } else {
                    println("Unexpected data type for key '${dataKey}': ${jsonResponse[dataKey].getClass()}")
                }
            } else {
                println("Key '${dataKey}' not found in response")
            }
    
            println("nextPageStart before update: ${nextPageStart}")
    
            nextPageStart = jsonResponse.nextPageStart    
            if (nextPageStart == null) {
                break
            }
        } catch (Exception e){
            println("Error: ${e}")
        } 
        
    }
    return allData
}