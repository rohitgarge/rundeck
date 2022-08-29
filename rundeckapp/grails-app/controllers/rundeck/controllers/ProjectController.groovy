/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rundeck.controllers

import com.dtolabs.rundeck.app.api.project.ProjectExport
import com.dtolabs.rundeck.app.support.ExecutionCleanerConfigImpl
import com.dtolabs.rundeck.app.support.ProjectArchiveExportRequest
import com.dtolabs.rundeck.app.support.ProjectArchiveParams
import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.authorization.Validation
import com.dtolabs.rundeck.core.common.FrameworkResource
import com.dtolabs.rundeck.core.common.IFramework
import com.dtolabs.rundeck.core.common.IRundeckProject
import com.dtolabs.rundeck.app.api.ApiVersions
import grails.compiler.GrailsCompileStatic
import grails.converters.JSON
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.rundeck.app.acl.AppACLContext
import org.rundeck.app.acl.ContextACLManager
import org.rundeck.core.auth.AuthConstants
import org.rundeck.core.auth.access.AuthActions
import org.rundeck.core.auth.app.RundeckAccess
import org.rundeck.core.auth.web.RdAuthorizeApplicationType
import org.rundeck.core.auth.web.RdAuthorizeProject
import rundeck.services.ApiService
import rundeck.services.ArchiveOptions
import com.dtolabs.rundeck.util.JsonUtil
import rundeck.services.ExecutionService
import rundeck.services.FrameworkService
import rundeck.services.ProjectService
import rundeck.services.ProjectServiceException
import rundeck.services.ScheduledExecutionService
import webhooks.component.project.WebhooksProjectComponent
import webhooks.exporter.WebhooksProjectExporter
import webhooks.importer.WebhooksProjectImporter

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.text.SimpleDateFormat
import org.apache.commons.fileupload.util.Streams
import org.springframework.web.multipart.MultipartHttpServletRequest

class ProjectController extends ControllerBase{
    FrameworkService frameworkService
    ProjectService projectService
    def scheduledExecutionService
    ContextACLManager<AppACLContext> aclFileManagerService
    def static allowedMethods = [
            apiProjectConfigKeyDelete:['DELETE'],
            apiProjectConfigKeyPut:['PUT'],
            apiProjectConfigPut:['PUT'],
            apiProjectFilePut:['PUT'],
            apiProjectFileDelete:['DELETE'],
            apiProjectCreate:['POST'],
            apiProjectDelete:['DELETE'],
            apiProjectImport: ['PUT'],
            apiProjectAcls:['GET','POST','PUT','DELETE'],
            importArchive: ['POST'],
            delete: ['POST'],
    ]

    def index () {
        return redirect(controller: 'menu', action: 'jobs')
    }

    @GrailsCompileStatic
    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_EXPORT)
    public def export(ProjectArchiveParams archiveParams){
        if (archiveParams.hasErrors()) {
            flash.errors = archiveParams.errors
            return redirect(controller: 'menu', action: 'projectExport', params: [project: params.project])
        }

        def authorizing = authorizingProject
        def project1 = authorizing.resource
        String project = project1.name

        ProjectArchiveExportRequest options = archiveParams.toArchiveOptions()
        //temp file
        File outfile
        try {
            outfile = projectService.exportProjectToFile(project1, frameworkService.getRundeckFramework(), null, options, authorizing.authContext)
        } catch (ProjectServiceException exc) {
            return renderErrorView(exc.message)
        }
        SimpleDateFormat dateFormater = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        def dateStamp = dateFormater.format(new Date());
        //output the file as an attachment
        response.setContentType("application/zip")
        response.setHeader("Content-Disposition", "attachment; filename=\"${project}-${dateStamp}.rdproject.jar\"")

        outfile.withInputStream {instream->
            Streams.copy(instream,response.outputStream,false)
        }
        outfile.delete()
    }
    /**
     * Async version of export, acquires a token and redirects to exportWait
     * @param archiveParams
     * @return
     */
    @GrailsCompileStatic
    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_EXPORT)
    public def exportPrepare(ProjectArchiveParams archiveParams){
        if (archiveParams.hasErrors()) {
            flash.errors = archiveParams.errors
            return redirect(controller: 'menu', action: 'projectExport', params: [project: params.project])
        }
        if (params.cancel) {
            return redirect(controller: 'menu', action: 'index', params: [project: params.project])
        }
        def authorizing = authorizingProject
        def project1 = authorizing.resource

        //request token

        archiveParams.cleanComponentOpts()
        //validate component input options
        def validations = projectService.validateAllProjectComponentExportOptions(archiveParams.exportOpts)
        if (validations.values().any { !it.valid }) {
            flash.validations = validations
            flash.componentValues = archiveParams.exportOpts
            flash.error = 'Some input was invalid'
            return redirect(controller: 'menu', action: 'projectExport', params: [project: params.project])
        }

        ProjectArchiveExportRequest options = archiveParams.toArchiveOptions()
        def token = projectService.exportProjectToFileAsync(
                project1,
                frameworkService.getRundeckFramework(),
                authorizing.authContext.username,
                options,
                authorizing.authContext
            )
        return redirect(action:'exportWait',params: [token:token,project:archiveParams.project])
    }

    @GrailsCompileStatic
    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_PROMOTE)
    public def exportInstancePrepare(ProjectArchiveParams archiveParams){
        def error = 0
        def msg = 'In order to export'
        if(!params.url){
            error++
            msg += ", Server URL"
        }
        if(!params.apitoken){
            error++
            msg += ", API Token"
        }
        if(!params.targetproject){

            msg += ", Target Project"
            error++
        }
        if (error>0) {
            if(error == 1){
                msg+=" is required."
            }else{
                msg+=" are required."
            }
            flash.error = msg
            return redirect(controller: 'menu', action: 'projectExport', params: [project: params.project])
        }
        params.instance = params.url
        if (archiveParams.hasErrors()) {
            flash.errors = archiveParams.errors
            return redirect(controller: 'menu', action: 'projectExport', params: [project: params.project])
        }
        if (params.cancel) {
            return redirect(controller: 'menu', action: 'index', params: [project: params.project])
        }
        IFramework framework = frameworkService.getRundeckFramework()

        def authorizing = authorizingProject
        def project1 = authorizing.resource
        String project = project1.name


        archiveParams.cleanComponentOpts()
        //validate component input options
        def validations = projectService.validateAllProjectComponentExportOptions(archiveParams.exportOpts)
        if (validations.values().any { !it.valid }) {
            flash.validations=validations
            flash.componentValues=archiveParams.exportOpts
            flash.error='Some input was invalid'
            return redirect(controller: 'menu', action: 'projectExport', params: [project: params.project])
        }

        ProjectArchiveExportRequest options = archiveParams.toArchiveOptions()
        def token = projectService.exportProjectToInstanceAsync(
            project1,
            framework,
            authorizing.authContext.username,
            options,
            archiveParams.targetproject,
            archiveParams.apitoken,
            archiveParams.url,
            archiveParams.preserveuuid,
            authorizing.authContext
        )
        return redirect(action: 'exportWait',
                        params: [
                            token: token,
                            project: archiveParams.project,
                            instance: params.instance,
                            iproject: archiveParams.targetproject]
        )
    }


    /**
     * poll for archive export process completion using a token, responds in html or json
     * @param token
     * @return
     */
    public def exportWait(){
        def token = params.token
        def instance = params.instance
        def iproject = params.iproject
        if (!token) {
            return withFormat {
                html {
                    request.errorMessage = 'token is required'
                    response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    render(view: "/common/error", model: [:])
                }
                json {
                    render(contentType: 'application/json') {
                        delegate.'token' token
                        delegate.'errorMessage' 'token is required'
                    }
                }
            }
        }
        if(!projectService.hasPromise(session.user,token)){
            return withFormat{
                html{
                    request.errorCode = 'request.error.notfound.message'
                    request.errorArgs = ["Export Request Token", token]
                    response.status = HttpServletResponse.SC_NOT_FOUND
                    request.titleCode = 'request.error.notfound.title'
                    return render(view: "/common/error",model:[:])
                }

                json{
                    render(contentType:'application/json'){
                        delegate.'token' token
                        delegate.'notFound' true
                    }
                }
            }
        }
        if(projectService.promiseError(session.user,token)){
            def errorMessage="Project export request failed: "+projectService.promiseError(session.user,token).message
            return withFormat{
                html{
                    request.errorMessage = errorMessage
                    response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    return render(view: "/common/error",model:[:])
                }
                json{
                    render(contentType:'application/json'){
                        delegate.'token' token
                        delegate.'errorMessage' errorMessage
                    }
                }
            }
        }
        if(instance){
            def result = projectService.promiseResult(session.user, token)
            if (result && !result.ok) {
                def errorList = []
                errorList.addAll(result.errors?:[])
                errorList.addAll(result.executionErrors?:[])
                errorList.addAll(result.aclErrors?:[])
                return withFormat{
                    html{
                        request.errors = errorList
                        response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                        return render(view: "/common/error",model:[:])
                    }
                    json{
                        render(contentType:'application/json'){
                            delegate.'token' token
                            delegate.'errors' errorList
                        }
                    }
                }
            }
        }
        File outfile = projectService.promiseReady(session.user,token)
        if(null!=outfile && params.download=='true'){
            SimpleDateFormat dateFormater = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            Date date=projectService.promiseRequestStarted(session.user,token)
            def dateStamp = dateFormater.format(null!=date?date:new Date());
            //output the file as an attachment
            response.setContentType("application/zip")
            response.setHeader("Content-Disposition", "attachment; filename=\"${params.project}-${dateStamp}.rdproject.jar\"")

            outfile.withInputStream {instream->
                Streams.copy(instream,response.outputStream,false)
            }
            projectService.releasePromise(session.user,token)
        }else {
            def percentage = projectService.promiseSummary(session.user, token).percent()

            return withFormat {
                html {
                    if(instance) {
                        render(view: "/menu/wait", model: [token   : token, ready: null != outfile, percentage: percentage,
                                                           instance: instance, iproject: iproject])
                    }else{
                        render(view: "/menu/wait", model: [token   : token, ready: null != outfile, percentage: percentage])
                    }
                }
                json {
                    render(contentType: 'application/json') {
                        delegate.'token' token
                        delegate.ready null != outfile
                        delegate.'percentage' percentage
                    }
                }
            }

        }
    }

//    @GrailsCompileStatic //TODO: change use of flash context vars
    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_IMPORT)
    public def importArchive(ProjectArchiveParams archiveParams){
        withForm{
            if(archiveParams.hasErrors()){
                flash.errors=archiveParams.errors
                return redirect(controller: 'menu', action: 'projectImport', params: [project: params.project])
            }
            if (params.cancel) {

                return redirect(controller: 'menu', action: 'index', params: [project: params.project])
            }

            def authorizing = authorizingProject
            def projectObj = authorizing.resource
            String project = projectObj.name

            AuthContext appContext = authorizing.authContext
            //TODO: replace with authorizingResource access
            //verify acl create access requirement
            if (archiveParams.importACL &&
                    unauthorizedResponse(
                        rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                                appContext,
                                rundeckAuthContextProcessor.authResourceForProjectAcl(project),
                                [AuthConstants.ACTION_CREATE, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                        ),
                        AuthConstants.ACTION_CREATE,
                        "ACL for Project",
                        project
                    )
            ) {
                return null
            }
            //validate component input options
            archiveParams.cleanComponentOpts()
            def validations = projectService.validateAllProjectComponentImportOptions(archiveParams.importOpts)
            if (validations.values().any { !it.valid }) {
                flash.validations = validations
                flash.componentValues=archiveParams.importOpts
                flash.error='Some input was invalid'
                return redirect(controller: 'menu', action: 'projectImport', params: [project: params.project])
            }

            def project1 = frameworkService.getFrameworkProject(project)

            //uploaded file
            if (request instanceof MultipartHttpServletRequest) {
                def file = request.getFile("zipFile")
                if (!file || file.empty) {
                    flash.error = message(code:"no.file.was.uploaded")
                    return redirect(controller: 'menu', action: 'projectImport', params: [project: project])
                }

                try{
                    def result = projectService.importToProject(
                        project1,
                        frameworkService.getRundeckFramework(),
                        authorizing.authContext,
                        file.getInputStream(),
                        archiveParams
                    )


                if(result.success){
                    if(result.execerrors){
                        flash.message=message(code:"archive.jobs.imported.some.executions.could.not.be.imported")
                    }else{
                        flash.message=message(code:"archive.successfully.imported")
                    }
                }else{
                    flash.error=result.joberrors
                    flash.joberrors=result.joberrors
                }
                def warning = []
                if(result.execerrors){
                    warning.add(result.execerrors)
                }
                if(result.aclerrors){
                    warning.add(result.aclerrors)
                }
                if(result.scmerrors){
                    warning.add(result.scmerrors)
                }
                if(result.importerErrors) {
                    result.importerErrors.each { k, v ->
                        warning.addAll(v)
                    }
                }
                flash.warn=warning.join(",")
                }catch( Exception e ){
                    flash.error="There was some errors with the import process: [ ${e.getMessage()} ]"
                }

                return redirect(controller: 'menu', action: 'projectImport', params: [project: project])
            }
        }.invalidToken {
            flash.error = g.message(code:'request.error.invalidtoken.message')
            return redirect(controller: 'menu', action: 'projectImport', params: [project: params.project])
        }
    }

    @RdAuthorizeProject(RundeckAccess.General.AUTH_APP_DELETE)
    def delete (ProjectArchiveParams archiveParams){
        withForm{
            if (archiveParams.hasErrors()) {
                flash.errors = archiveParams.errors
                return redirect(controller: 'menu', action: 'projectDelete', params: [project: params.project])
            }

            def authorizing = authorizingProject
            def project1 = authorizing.resource
            String project = project1.name

            def result = projectService.deleteProject(
                project1,
                frameworkService.getRundeckFramework(),
                authorizing.authContext,
                authorizing.authContext.username
            )
            if (!result.success) {
                log.error("Failed to delete project: ${result.error}")
                flash.error = result.error
                return redirect(controller: 'menu', action: 'projectDelete', params: [project: project])
            }
            flash.message = 'Deleted project: ' + project
            return redirect(controller: 'menu', action: 'home')
        }.invalidToken {
            flash.error= g.message(code: 'request.error.invalidtoken.message')
            return redirect(controller: 'menu', action: 'projectDelete', params: [project: params.project])
        }
    }

    /**
     * Render project XML result using a builder
     * @param pject framework project object
     * @param delegate builder delegate for response
     * @param hasConfigAuth true if 'configure' action is allowed
     * @param vers api version requested
     */
    @PackageScope
    def renderApiProjectXml (def pject, delegate, hasConfigAuth=false, vers=1){
        Map data = basicProjectDetails(pject,vers)
        def pmap = [url: data.url]
        delegate.'project'(pmap) {
            name(data.name)
            description(data.description)
            if (vers >= ApiVersions.V33) {
                created(data.created)
            }
            if (vers >= ApiVersions.V26) {
                if (pject.hasProperty("project.label")) {
                    label(data.label)
                }
            }
            if (hasConfigAuth) {
                //include config data
                renderApiProjectConfigXml(pject,delegate)
            }
        }


    }
    /**
     * Render project config XML content using a builder
     * @param pject framework project object
     * @param delegate builder delegate for response
     */
    @PackageScope
    def renderApiProjectConfigXml (def pject, delegate){
        delegate.'config' {
            frameworkService.loadProjectProperties(pject).each { k, v ->
                delegate.'property'(key: k, value: v)
            }
        }
    }

    /**
     * Render project JSON result using a builder
     * @param pject framework project object
     * @param delegate builder delegate for response
     * @param hasConfigAuth true if 'configure' action is allowed
     * @param vers api version requested
     */
    @PackageScope
    def renderApiProjectJson (def pject, hasConfigAuth=false, vers=1){
        Map data=basicProjectDetails(pject,vers)
        Map json = [url: data.url, name: data.name, description: data.description]
        if(data.label){
            json.label = data.label
        }
        if(data.created){
            json.created = ExecutionService.ISO_8601_DATE_FORMAT.get().format(data.created)
        }
        if(hasConfigAuth){
            json.config = frameworkService.loadProjectProperties(pject)
        }
        json
    }

    private Map basicProjectDetails(def pject, def version) {
        def name = pject.name
        def retMap = [
                url:generateProjectApiUrl(pject.name),
                name: name,
                description: pject.getProjectProperties()?.get("project.description")?:''
        ]
        if(version>=ApiVersions.V26){
            retMap.label = pject.getProjectProperties()?.get("project.label")?:''
        }
        if(version>=ApiVersions.V33){
            def created = pject.getConfigCreatedTime()
            if(created){
                retMap.created = created?:''
            }
        }
        retMap

    }

    /**
     * Generate absolute api URL for the project
     * @param projectName
     * @return
     */
    private String generateProjectApiUrl(String projectName) {
        g.createLink(absolute: true, uri: "/api/${ApiVersions.API_CURRENT_VERSION}/project/${projectName}")
    }

    /**
     * API: /api/11/projects
     */
    def apiProjectList(){
        if (!apiService.requireApi(request, response)) {
            return
        }
        def projlist = frameworkService.projects(systemAuthContext)
        withFormat{

            xml{
                apiService.renderSuccessXml(request, response) {
                    delegate.'projects'(count: projlist.size()) {
                        projlist.sort { a, b -> a.name <=> b.name }.each { pject ->
                            //don't include config data
                            renderApiProjectXml(pject, delegate, false, request.api_version)
                        }
                    }
                }
            }
            json{
                List details = []
                projlist.sort { a, b -> a.name <=> b.name }.each { pject ->
                    //don't include config data
                    details.add(basicProjectDetails(pject,request.api_version))
                }

                render details as JSON
            }
        }

    }

    /**
     * API: /api/11/project/NAME
     */
    @RdAuthorizeProject(RundeckAccess.General.AUTH_APP_READ)
    def apiProjectGet() {
        if (!apiService.requireApi(request, response)) {
            return
        }
        def configAuth = authorizingProject.isAuthorized(RundeckAccess.Project.APP_CONFIGURE)
        def pject = authorizingProject.resource
        withFormat{
            xml{

                apiService.renderSuccessXml(request, response) {
                    renderApiProjectXml(pject, delegate, configAuth, request.api_version)
                }
            }
            json{
                render renderApiProjectJson(pject, configAuth, request.api_version) as JSON
            }
        }
    }


    @RdAuthorizeApplicationType(
        type = AuthConstants.TYPE_PROJECT,
        access = RundeckAccess.General.AUTH_APP_CREATE
    )
    def apiProjectCreate() {
        if (!apiService.requireApi(request, response)) {
            return
        }
        //allow Accept: header, but default to the request format
        def respFormat = apiService.extractResponseFormat(request,response,['xml','json'])

        def project = null
        def description = null
        Map config = null
        //parse request format
        String errormsg=''
        def succeeded = apiService.parseJsonXmlWith(request,response, [
                xml: { xml ->
                    project = xml?.name[0]?.text()
                    description = xml?.description[0]?.text()
                    config = [:]
                    xml?.config?.property?.each {
                        config[it.'@key'.text()] = it.'@value'.text()
                    }
                },
                json: { json ->
                    def errors = JsonUtil.validateJson(json,[
                            '!name':String,
                            description:String,
                            config:Map
                    ])
                    if (errors) {
                        errormsg += errors.join("; ")
                        return
                    }
                    project = JsonUtil.jsonNull(json?.name)?.toString()
                    description = JsonUtil.jsonNull(json?.description)?.toString()
                    config = JsonUtil.jsonNull(json?.config)
                }
        ])
        if(!succeeded){
            return
        }
        if (errormsg) {
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: "api.error.invalid.request",
                    args: [errormsg],
                    format: respFormat
            ])
            return
        }
        if( description){
            if(config && !config['project.description']){
                config['project.description']=description
            }else if(!config){
                config=[('project.description'):description]
            }
        }

        if (!project) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.invalid.request",
                            args: ["Project 'name' is required"],
                            format: respFormat
                    ])
        } else if (!(project =~ FrameworkResource.VALID_RESOURCE_NAME_REGEX)) {
            return apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "project.name.can.only.contain.these.characters",
                            args: [],
                            format: respFormat
                    ])
        }
        def exists = frameworkService.existsFrameworkProject(project)
        if (exists) {
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_CONFLICT,
                    code: 'api.error.item.alreadyexists',
                    args: ['project', project],
                    format: respFormat
            ])
        }
        def proj
        def errors
        (proj,errors)=frameworkService.createFrameworkProject(project,new Properties(config))
        if(errors){
            return apiService.renderErrorFormat(response,[status:HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message: errors.join('; '),format: respFormat])
        } else {
            String propsPrefix = "project.execution.history.cleanup"
            def cleanerEnabled = config && ["true", true].contains(config["${propsPrefix}.enabled"])
            if (cleanerEnabled) {
                frameworkService.scheduleCleanerExecutions(
                    project,
                    ExecutionCleanerConfigImpl.build {
                        enabled(cleanerEnabled)
                        maxDaysToKeep(
                            FrameworkService.tryParseInt(config["${propsPrefix}.retention.days"]).orElse(-1)
                        )
                        minimumExecutionToKeep(
                            FrameworkService.tryParseInt(config["${propsPrefix}.retention.minimum"]).orElse(0)
                        )
                        maximumDeletionSize(
                            FrameworkService.tryParseInt(config["${propsPrefix}.batch"]).orElse(500)
                        )
                        cronExpression(config["${propsPrefix}.schedule"])
                    }
                )
            }
        }
        switch(respFormat) {
            case 'xml':
                apiService.renderSuccessXml(HttpServletResponse.SC_CREATED,request, response) {
                    renderApiProjectXml(proj,delegate,true,request.api_version)
                }
                break
            case 'json':
                response.status = HttpServletResponse.SC_CREATED
                render renderApiProjectJson(proj, true, request.api_version) as JSON
                break
        }
    }

    @GrailsCompileStatic
    @RdAuthorizeProject(RundeckAccess.General.AUTH_APP_DELETE)
    def apiProjectDelete(){
        if (!apiService.requireApi(request, response)) {
            return
        }
        def authorizing = authorizingProject
        def project1 = authorizing.resource

        ProjectService.DeleteResponse result = projectService.deleteProject(
            project1,
            frameworkService.getRundeckFramework(),
            authorizing.authContext,
            authorizing.authContext.username
        )
        if (!result.success) {
            return apiService.renderErrorFormat(
                response,
                new HashMap<>(
                    [
                        status : HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        code   : "api.error.unknown",
                        message: result.error,
                    ]
                )
            )
        }
        //success
        render(status:  HttpServletResponse.SC_NO_CONTENT)
    }
    /**
     * support project/NAME/config and project/NAME/acl endpoints: validate project and appropriate authorization,
     * return null if invalid and a response has already been sent.
     * @param actions auth actions
     * @return FrameworkProject for the project
     */
    private IRundeckProject validateProjectConfigApiRequest(AuthActions actions = RundeckAccess.Project.APP_CONFIGURE){
        if (!apiService.requireApi(request, response)) {
            return null
        }
        return authorizingProject.access(actions)
    }
    /**
     * support project/NAME/config and project/NAME/acl endpoints: validate project and appropriate authorization,
     * return null if invalid and a response has already been sent.
     * @param action action to require
     * @return FrameworkProject for the project
     */
    private def validateProjectAclApiRequest(String action){
        if (!apiService.requireApi(request, response)) {
            return
        }
        String project = params.project
        if (!project) {
            apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.parameter.required",
                            args: ['project']
                    ])
            return null
        }
        if (!frameworkService.existsFrameworkProject(project)) {
            apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_NOT_FOUND,
                            code: "api.error.item.doesnotexist",
                            args: ['Project', project]
                    ])
            return null
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)

        if (!rundeckAuthContextProcessor.authorizeApplicationResourceAny(authContext,
                rundeckAuthContextProcessor.authResourceForProjectAcl(project),
                [action, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN])) {
            apiService.renderErrorFormat(response,
                    [
                            status: HttpServletResponse.SC_FORBIDDEN,
                            code: "api.error.item.unauthorized",
                            args: [action, "ACL for Project", project]
                    ])
            return null
        }
        return frameworkService.getFrameworkProject(project)
    }
    @GrailsCompileStatic
    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_CONFIGURE)
    def apiProjectConfigGet(){
        if (!apiService.requireApi(request, response)) {
            return
        }
        def proj = authorizingProject.resource
        //render project config only

        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json','text'], 'xml')
        respondProjectConfig(respFormat, proj)
    }

    @CompileStatic
    @PackageScope
    void respondProjectConfig(String respFormat, IRundeckProject proj) {
        switch (respFormat) {
            case 'text':
                response.setContentType("text/plain")
                def props = proj.getProjectProperties() as Properties
                props.store(response.outputStream, request.forwardURI)
                flush(response)
                break
            case 'xml':
                apiService.renderSuccessXml(request, response) {
                    renderApiProjectConfigXml(proj, delegate)
                }
                break
            case 'json':
                def config=frameworkService.loadProjectProperties(proj)
                render(config as JSON)
                break
        }
    }
    /**
     * /api/14/project/NAME/acl/* endpoint
     */
    def apiProjectAcls() {
        if (!apiService.requireApi(request, response, ApiVersions.V14)) {
            return
        }

        def project = validateProjectAclApiRequest(ApiService.HTTP_METHOD_ACTIONS[request.method])
        if (!project) {
            return
        }
        if(params.path && !(params.path ==~ /^[^\/]+.aclpolicy$/ )){
            def respFormat = apiService.extractResponseFormat(request, response, ['xml','json','text'],request.format)
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.parameter.invalid',
                    args:[params.path,'path','Must refer to a file ending in .aclpolicy'],
                    format:respFormat
            ])
        }
        String projectFilePath = params.path.toString()
        switch (request.method) {
            case 'POST':
            case 'PUT':
                apiProjectAclsPutResource(project, projectFilePath, request.method == 'POST')
                break
            case 'GET':
                if(projectFilePath){
                    apiProjectAclsGetResource(project, projectFilePath)
                }else{
                    apiProjectAclsGetListing(project)
                }
                break
            case 'DELETE':
                apiProjectAclsDeleteResource(project, projectFilePath)
                break
        }
    }

    /**
     * Create or update resource  for the specified project path
     * @param project project
     * @param projectFilePath path for the project file
     * @param create if true, attempt to create, if false update existing
     * @return
     */
    private def apiProjectAclsPutResource(IRundeckProject project, String projectFilePath, boolean create) {
        def respFormat = apiService.extractResponseFormat(request, response, ['xml','json','yaml','text'],request.format)
        def exists = aclFileManagerService.existsPolicyFile(AppACLContext.project(project.name), projectFilePath)
        if(create && exists) {
            //conflict
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_CONFLICT,
                    code  : 'api.error.item.alreadyexists',
                    args  : ['Project ACL Policy File', params.path + ' for project ' + project.name],
                    format: respFormat
            ]
            )
        }else if(!create && !exists){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_NOT_FOUND,
                    code  : 'api.error.item.doesnotexist',
                    args  : ['Project ACL Policy File', params.path + ' for project ' + project.name],
                    format: respFormat
            ]
            )
        }

        def error = null
        String text = null
        if (request.format in ['yaml','text']) {
            try {
                text = request.inputStream.text
            } catch (Throwable e) {
                error = e.message
            }
        }else{
            def succeeded = apiService.parseJsonXmlWith(request,response,[
                    xml:{xml->
                        if(xml?.name()=='contents'){
                            text=xml?.text()
                        }else{
                            text = xml?.contents[0]?.text()
                        }
                    },
                    json:{json->
                        text = json?.contents
                    }
            ])
            if(!succeeded){
                error= "unexpected format: ${request.format}"
                return
            }
        }
        if(error){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    message: error,
                    format: respFormat
            ])
        }

        if(!text){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    message: "No content",
                    format: respFormat
            ])
        }

        //validate input
        Validation validation = aclFileManagerService.validateYamlPolicy(AppACLContext.project(project.name), params.path, text)
        if(!validation.valid){
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return withFormat{
                def j={
                    render apiService.renderJsonAclpolicyValidation(validation) as JSON
                }
                xml{
                    render(contentType: 'application/xml'){
                        apiService.renderXmlAclpolicyValidation(validation,delegate)
                    }
                }
                json j
                '*' j
            }
        }

        aclFileManagerService.storePolicyFileContents(AppACLContext.project(project.name), projectFilePath, text)

        response.status=create ? HttpServletResponse.SC_CREATED : HttpServletResponse.SC_OK
        if(respFormat in ['yaml','text']){
            //write directly
            response.setContentType(respFormat=='yaml'?"application/yaml":'text/plain')
            aclFileManagerService.
                loadPolicyFileContents(AppACLContext.project(project.name), projectFilePath, response.outputStream)
            flush(response)
        }else{
            def baos=new ByteArrayOutputStream()
            aclFileManagerService.loadPolicyFileContents(AppACLContext.project(project.name), projectFilePath, baos)
            withFormat{
                json{
                    def content = [contents: baos.toString()]
                    render content as JSON
                }
                xml{
                    render(contentType: 'application/xml'){
                        apiService.renderWrappedFileContentsXml(baos.toString(),respFormat,delegate)
                    }

                }
            }
        }
    }



    private def renderProjectAclHref(String project,String path) {
        createLink(absolute: true, uri: "/api/${ApiVersions.API_CURRENT_VERSION}/project/$project/acl/$path")
    }

    /**
     * Get resource  for the specified project path
     * @param project project
     * @param projectFilePath path for the project file
     * @return
     */
    private def apiProjectAclsGetResource(IRundeckProject project,String projectFilePath) {
        def respFormat = apiService.extractResponseFormat(request, response, ['yaml','xml','json','text','all'],response.format?:'json')
        if(aclFileManagerService.existsPolicyFile(AppACLContext.project(project.name),projectFilePath)){
            if(respFormat in ['yaml','text']){
                //write directly
                response.setContentType(respFormat=='yaml'?"application/yaml":'text/plain')
                aclFileManagerService.loadPolicyFileContents(AppACLContext.project(project.name),projectFilePath, response.outputStream)
                flush(response)
            }else if(respFormat in ['json','xml','all'] ){
                //render as json/xml with contents as string
                def baos=new ByteArrayOutputStream()
                aclFileManagerService.loadPolicyFileContents(AppACLContext.project(project.name),projectFilePath, baos)
                withFormat{
                    json{
                        def content = [contents: baos.toString()]
                        render content as JSON
                    }
                    xml{
                        render(contentType: 'application/xml'){
                            apiService.renderWrappedFileContentsXml(baos.toString(),'xml',delegate)
                        }

                    }
                }
            }else{
                apiService.renderErrorFormat(response,[
                        status:HttpServletResponse.SC_NOT_ACCEPTABLE,
                        code:'api.error.resource.format.unsupported',
                        args:[respFormat]
                ])
            }
        }else{

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist',
                    args:['resource',params.path],
                    format: respFormat
            ])
        }

    }
    /**
     * Get acls listing for the specified project
     * @param project project
     * @return
     */
    private def apiProjectAclsGetListing(IRundeckProject project) {
        //list aclpolicy files in the dir
        def projectName = project.name
        def list = aclFileManagerService.listStoredPolicyFiles(AppACLContext.project(projectName))
        withFormat{
            json{
                render apiService.jsonRenderDirlist(
                            '',
                            {p->p},
                            {p->renderProjectAclHref(projectName,p)},
                            list
                    ) as JSON
            }
            xml{
                render(contentType: 'application/xml'){
                    apiService.xmlRenderDirList(
                            '',
                            {p->p},
                            {p->renderProjectAclHref(projectName,p)},
                            list,
                            delegate
                    )
                }

            }
        }
    }
    /**
     * Delete ACL resource
     * @param project project
     * @param projectFilePath file
     * @return
     */
    private def apiProjectAclsDeleteResource(IRundeckProject project,projectFilePath) {
        def respFormat = apiService.extractResponseFormat(request, response, ['xml','json','text'],request.format)
        def exists = aclFileManagerService.existsPolicyFile(AppACLContext.project(project.name),projectFilePath)
        if(!exists){

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_NOT_FOUND,
                    code  : 'api.error.item.doesnotexist',
                    args  : ['Project ACL Policy File', params.path + ' for project ' + project.name],
                    format: respFormat
            ])
        }
        boolean done=aclFileManagerService.deletePolicyFile(AppACLContext.project(project.name),projectFilePath)
        if(!done){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_CONFLICT,
                    message: "error",
                    format: respFormat
            ])
        }
        render(status: HttpServletResponse.SC_NO_CONTENT)
    }
    def apiProjectFilePut() {
        if (!apiService.requireApi(request, response, ApiVersions.V13)) {
            return
        }
        def project = validateProjectConfigApiRequest()
        if (!project) {
            return
        }
        def respFormat = apiService.extractResponseFormat(request, response, ['xml','json','text'],request.format)
        if(!(params.filename in ['readme.md','motd.md'])){

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist',
                    args:['resource',params.filename],
                    format:respFormat
            ])
        }

        def error = null
        String text = null
        if (request.format in ['text']) {
            try {
                text = request.inputStream.text
            } catch (Throwable e) {
                error = e.message
            }
        }else{
            def succeeded = apiService.parseJsonXmlWith(request,response,[
                    xml:{xml->
                        if(xml?.name()=='contents'){
                            text=xml?.text()
                        }else{
                            text = xml?.contents[0]?.text()
                        }
                    },
                    json:{json->
                        text = json?.contents
                    }
            ])
            if(!succeeded){
                error= "unexpected format: ${request.format}"
            }
        }
        if(error){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    message: error,
                    format: respFormat
            ])
        }

        if(!text){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    message: "No content",
                    format: respFormat
            ])
        }

        project.storeFileResource(params.filename,new ByteArrayInputStream(text.bytes))

        if(respFormat in ['text']){
            //write directly
            response.setContentType("text/plain")
            project.loadFileResource(params.filename,response.outputStream)
            flush(response)
        }else{

            def baos=new ByteArrayOutputStream()
            project.loadFileResource(params.filename,baos)
            renderProjectFile(baos.toString(),request,response, respFormat)
        }
    }

    private def renderProjectFile(
            String contentString,
            HttpServletRequest request,
            HttpServletResponse response,
            String respFormat
    )
    {
        if (respFormat=='json') {
            def jsonContent = [contents: contentString]
            render jsonContent as JSON
        }else{
            apiService.renderSuccessXml(request, response) {
                delegate.'contents' {
                    mkp.yieldUnescaped("<![CDATA[" + contentString.replaceAll(']]>', ']]]]><![CDATA[>') + "]]>")
                }
            }
        }
    }
    def apiProjectFileGet() {
        if (!apiService.requireApi(request, response, ApiVersions.V13)) {
            return
        }
        def project = validateProjectConfigApiRequest()
        if (!project) {
            return
        }
        def respFormat = apiService.extractResponseFormat(request, response, ['xml','json','text'],'text')
        if(!(params.filename in ['readme.md','motd.md'])){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist',
                    args:['resource',params.filename],
                    format:respFormat
            ])
        }
        if(!project.existsFileResource(params.filename)){

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist',
                    args:['resource',params.filename],
                    format: respFormat
            ])
        }

        if(respFormat in ['text']){
            //write directly
            response.setContentType("text/plain")
            project.loadFileResource(params.filename,response.outputStream)
            flush(response)
        }else{

            def baos=new ByteArrayOutputStream()
            project.loadFileResource(params.filename,baos)
            renderProjectFile(baos.toString(),request,response, respFormat)
        }
    }
    def apiProjectFileDelete() {
        if (!apiService.requireApi(request, response, ApiVersions.V13)) {
            return
        }
        def project = validateProjectConfigApiRequest()
        if (!project) {
            return
        }
        def respFormat = apiService.extractResponseFormat(request, response, ['xml','json','text'])
        if(!(params.filename in ['readme.md','motd.md'])){

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist',
                    args:['resource',params.filename],
                    format:respFormat
            ])
        }

        boolean done=project.deleteFileResource(params.filename)
        if(!done){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_CONFLICT,
                    message: "error",
                    format: respFormat
            ])
        }
        render(status: HttpServletResponse.SC_NO_CONTENT)
    }
    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_CONFIGURE)
    def apiProjectConfigPut() {
        if(!apiService.requireApi(request,response)){
            return
        }
        def project = authorizingProject.resource
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json', 'text'])
        //parse config data
        def config=null
        def configProps=new Properties()
        if (request.format in ['text']) {
            def error=null
            try{
                configProps.load(request.inputStream)
            }catch (Throwable t){
                error=t.message
            }
            if(error){
                return apiService.renderErrorFormat(response, [
                        status: HttpServletResponse.SC_BAD_REQUEST,
                        message: error,
                        format: respFormat
                ])
            }
        }else{
            def succeed = apiService.parseJsonXmlWith(request, response, [
                    xml: { xml ->
                        config = [:]
                        xml?.property?.each {
                            config[it.'@key'.text()] = it.'@value'.text()
                        }
                    },
                    json: { json ->
                        config = json
                    }
            ])
            if(!succeed){
                return
            }
            configProps.putAll(config)
        }
        Properties currentProps = project.getProjectProperties() as Properties

        def result=frameworkService.setFrameworkProjectConfig(project.name,configProps)

        if(!result.success){
            return apiService.renderErrorFormat(response,[
                    status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message:result.error,
                    format:respFormat
            ])
        }
        checkScheduleChanges(project, currentProps, configProps)
        respondProjectConfig(respFormat, project)
    }
    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_CONFIGURE)
    def apiProjectConfigKeyGet() {
        if(!apiService.requireApi(request,response)){
            return
        }
        def project = authorizingProject.resource
        def key_ = apiService.restoreUriPath(request, params.keypath)
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json','text'],'text')
        def properties = frameworkService.loadProjectProperties(project)
        if(null==properties.get(key_)){
            return apiService.renderErrorFormat(response,[
                    status:HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist',
                    args:['property',key_],
                    format:respFormat
            ])
        }
        def value_ = properties.get(key_)
        switch (respFormat) {
            case 'text':
                render (contentType: 'text/plain', text: value_)
                break
            case 'xml':
                apiService.renderSuccessXml(request, response) {
                    property(key:key_,value:value_)
                }
                break
            case 'json':
                render(contentType: 'application/json') {
                    key key_
                    value value_
                }
                break
        }
    }

    private void checkScheduleChanges(IRundeckProject project, Map propsBeforeChange, Map changedProps){
        def execDisabledChange = changedProps[ScheduledExecutionService.CONF_PROJECT_DISABLE_EXECUTION]
        def schedDisabledChange = changedProps[ScheduledExecutionService.CONF_PROJECT_DISABLE_SCHEDULE]

        if(execDisabledChange != null || schedDisabledChange != null){
            def disableEx = propsBeforeChange.get("project.disable.executions")
            def disableSched = propsBeforeChange.get("project.disable.schedule")

            boolean isExecutionDisabledNow  = disableEx && disableEx == 'true'
            boolean isScheduleDisabledNow = disableSched && disableSched == 'true'
            def newExecutionDisabledStatus =
                    execDisabledChange == 'true'
            def newScheduleDisabledStatus =
                    schedDisabledChange == 'true'

            boolean reschedule = ((isExecutionDisabledNow != newExecutionDisabledStatus)
                    || (isScheduleDisabledNow != newScheduleDisabledStatus))
            if(reschedule){
                frameworkService.handleProjectSchedulingEnabledChange(
                        project?.getName(),
                        isExecutionDisabledNow,
                        isScheduleDisabledNow,
                        newExecutionDisabledStatus,
                        newScheduleDisabledStatus
                )
            }
        }
    }

    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_CONFIGURE)
    def apiProjectConfigKeyPut() {
        if(!apiService.requireApi(request,response)){
            return
        }
        def project = authorizingProject.resource
        def key_ = apiService.restoreUriPath(request, params.keypath)
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json', 'text'])
        def value_=null
        if(request.format in ['text']){
           value_ = request.inputStream.text
        }else{
            def succeeded = apiService.parseJsonXmlWith(request,response,[
                    xml:{xml->
                        value_ = xml?.'@value'?.text()
                    },
                    json:{json->
                        value_ = json?.value
                    }
            ])
            if(!succeeded){
                return
            }
        }
        if(!value_){
            return apiService.renderErrorFormat(response,[
                    status:HttpServletResponse.SC_BAD_REQUEST,
                    code:'api.error.invalid.request',
                    args:["value was not specified"],
                    format:respFormat
            ])
        }
        def propValueBefore = project.getProperty(key_)
        if(propValueBefore){
            propValueBefore = new Properties([(key_): propValueBefore])
        }else{
            propValueBefore = new Properties([(key_): ''])
        }

        def result=frameworkService.updateFrameworkProjectConfig(project.name,new Properties([(key_): value_]),null)

        if(!result.success){
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message:result.error,
                    format: respFormat
            ])
        }
        def resultValue= project.getProperty(key_)
        propValueBefore
        checkScheduleChanges(project, propValueBefore, new Properties([(key_): resultValue]))

        switch (respFormat) {
            case 'text':
                render(contentType: 'text/plain', text: resultValue)
                break
            case 'xml':
                apiService.renderSuccessXml(request, response) {
                    property(key: key_, value: resultValue)
                }
                break
            case 'json':
                render(contentType: 'application/json') {
                    key key_
                    value value_
                }
                break
        }
    }
    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_CONFIGURE)
    def apiProjectConfigKeyDelete() {
        if (!apiService.requireApi(request, response)) {
            return null
        }
        def project = authorizingProject.resource
        def key = apiService.restoreUriPath(request, params.keypath)
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json','text'],'json')

        def result=frameworkService.removeFrameworkProjectConfigProperties(project.name,[key] as Set)
        if (!result.success) {
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    message: result.error,
                    format: respFormat
            ])
        }
        render(status: HttpServletResponse.SC_NO_CONTENT)
    }

    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_EXPORT)
    def apiProjectExport(ProjectArchiveParams archiveParams) {
        if(!apiService.requireApi(request,response)){
            return
        }
        def project = authorizingProject.resource
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json'], 'xml')
        if (archiveParams.hasErrors()) {
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code  : 'api.error.invalid.request',
                    args  : [archiveParams.errors.allErrors.collect { g.message(error: it) }.join("; ")],
                    format: respFormat
            ]
            )
        }
        if (params.async) {
            if (!apiService.requireApi(request, response, ApiVersions.V19)) {
                return
            }
        }
        def framework = frameworkService.rundeckFramework

        AuthContext authContext = systemAuthContext
        if (request.api_version < ApiVersions.V28) {
            archiveParams.exportScm = false
        }
        archiveParams.cleanComponentOpts()

        //nb: compatibility with API v34
        if (request.api_version>= ApiVersions.V34 && (params.exportWebhooks == 'true'||params.exportAll == 'true')) {
            if (archiveParams.exportComponents != null) {
                archiveParams.exportComponents[WebhooksProjectComponent.COMPONENT_NAME] = true
            } else {
                archiveParams.exportComponents = [(WebhooksProjectComponent.COMPONENT_NAME): true]
            }
            if (archiveParams.exportOpts == null) {
                archiveParams.exportOpts = [:]
            }
            if (archiveParams.exportOpts[WebhooksProjectComponent.COMPONENT_NAME] == null) {
                archiveParams.exportOpts.put(WebhooksProjectComponent.COMPONENT_NAME, [:])
            }
            archiveParams.exportOpts[WebhooksProjectComponent.COMPONENT_NAME][
                WebhooksProjectExporter.INLUDE_AUTH_TOKENS] = params.whkIncludeAuthTokens
        }

        ProjectArchiveExportRequest options
        if (params.executionIds) {
            def archopts=new ArchiveOptions(all: false, executionsOnly: true)
            archopts.parseExecutionsIds(params.executionIds)
            options = archopts
        } else if (request.api_version >= ApiVersions.V19) {
            options = archiveParams.toArchiveOptions()
        } else {
            options = new ArchiveOptions(all: true)
        }
        if (params.async && params.async.asBoolean()) {

            def token = projectService.exportProjectToFileAsync(
                    project,
                    framework,
                    session.user,
                    options,
                    authContext
            )

            File outfile = projectService.promiseReady(session.user, token)
            def percentage = projectService.promiseSummary(session.user, token).percent()
            return respond(
                    new ProjectExport(token: token, ready: null != outfile, percentage: percentage),
                    [formats: ['xml', 'json']]
            )
        }
        SimpleDateFormat dateFormater = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        def dateStamp = dateFormater.format(new Date());
        response.setContentType("application/zip")
        response.setHeader("Content-Disposition", "attachment; filename=\"${project.name}-${dateStamp}.rdproject.jar\"")
        projectService.exportProjectToOutputStream(
                project,
                framework,
                response.outputStream,
                null,
                options,
                authContext
        )
    }


    def apiProjectExportAsyncStatus() {
        def token = params.token
        if (!apiService.requireApi(request, response, ApiVersions.V19)) {
            return
        }
        if (!apiService.requireParameters(params, response, ['token'])) {
            return
        }
        if (!apiService.requireExists(
                response,
                projectService.hasPromise(session.user, token),
                ['Export Request Token', token]
        )) {
            return
        }

        if (projectService.promiseError(session.user, token)) {
            apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    code  : 'api.error.project.archive.failure',
                    args  : [token, projectService.promiseError(session.user, token).message]
            ]
            )
        }
        File outfile = projectService.promiseReady(session.user, token)
        def percentage = projectService.promiseSummary(session.user, token).percent()
        respond(
                new ProjectExport(token: token, ready: null != outfile, percentage: percentage),
                [formats: ['xml', 'json']]
        )
    }

    def apiProjectExportAsyncDownload() {
        def token = params.token
        if (!apiService.requireApi(request, response, ApiVersions.V19)) {
            return
        }
        if (!apiService.requireParameters(params, response, ['token'])) {
            return
        }
        if (!apiService.requireExists(
                response,
                projectService.hasPromise(session.user, token),
                ['Export Request Token', token]
        )) {
            return
        }

        File outfile = projectService.promiseReady(session.user, token)
        if (!apiService.requireExists(
                response,
                outfile,
                ['Export File for Token', token]
        )) {
            return
        }

        SimpleDateFormat dateFormater = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        Date date = projectService.promiseRequestStarted(session.user, token)
        def dateStamp = dateFormater.format(null != date ? date : new Date());
        //output the file as an attachment
        response.setContentType("application/zip")
        response.setHeader(
                "Content-Disposition",
                "attachment; filename=\"${params.project}-${dateStamp}.rdproject.jar\""
        )

        outfile.withInputStream { instream ->
            Streams.copy(instream, response.outputStream, false)
        }
        projectService.releasePromise(session.user, token)
    }

    @RdAuthorizeProject(RundeckAccess.Project.AUTH_APP_IMPORT)
    def apiProjectImport(ProjectArchiveParams archiveParams){
        if(!apiService.requireApi(request,response)){
            return
        }
        def project = authorizingProject.resource
        if(!apiService.requireRequestFormat(request,response,['application/zip'])){
            return
        }
        def respFormat = apiService.extractResponseFormat(request, response, ['xml', 'json'], 'xml')
        if(archiveParams.hasErrors()){
            return apiService.renderErrorFormat(response,[
                    status:HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.invalid.request',
                    args: [archiveParams.errors.allErrors.collect{g.message(error: it)}.join("; ")],
                    format:respFormat
            ])
        }
        def framework = frameworkService.rundeckFramework

        AuthContext appContext = systemAuthContext
        //uploaded file

        //verify acl access requirement
        //TODO project acl authorized access
        if (archiveParams.importACL &&
                !rundeckAuthContextProcessor.authorizeApplicationResourceAny(
                        appContext,
                        rundeckAuthContextProcessor.authResourceForProjectAcl(project.name),
                        [AuthConstants.ACTION_CREATE, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_APP_ADMIN]
                )
        ) {

            apiService.renderErrorFormat(response,
                                         [
                                                 status: HttpServletResponse.SC_FORBIDDEN,
                                                 code  : "api.error.item.unauthorized",
                                                 args  : [AuthConstants.ACTION_CREATE, "ACL for Project", project.name]
                                         ]
            )
            return null
        }
        if (archiveParams.importScm && request.api_version >= ApiVersions.V28) {
            //verify scm access requirement
            if (archiveParams.importScm ) {
                authorizingProject.authorize(RundeckAccess.Project.APP_CONFIGURE)
            }
        }else{
            archiveParams.importScm=false
        }

        def stream = request.getInputStream()
        def len = request.getContentLength()
        if(0==len){
            return apiService.renderErrorFormat(response,[
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.invalid.request',
                    args: ['No content'],
                    format:respFormat
            ])
        }
        archiveParams.cleanComponentOpts()

        //nb: compatibility with API v34
        if (request.api_version>= ApiVersions.V34 && params.importWebhooks == 'true') {
            if (archiveParams.importComponents != null) {
                archiveParams.importComponents[WebhooksProjectComponent.COMPONENT_NAME] = true
            } else {
                archiveParams.importComponents = [(WebhooksProjectComponent.COMPONENT_NAME): true]
            }
            if (archiveParams.importOpts == null) {
                archiveParams.importOpts = [:]
            }
            if (archiveParams.importOpts[WebhooksProjectComponent.COMPONENT_NAME] == null) {
                archiveParams.importOpts.put(WebhooksProjectComponent.COMPONENT_NAME, [:])
            }
            archiveParams.importOpts[WebhooksProjectComponent.COMPONENT_NAME][
                WebhooksProjectImporter.WHK_REGEN_AUTH_TOKENS] = params.whkRegenAuthTokens
        }

        //previous version must import nodes together with the project config
        if(request.api_version <= ApiVersions.V38) {
            archiveParams.importNodesSources = archiveParams.importConfig
        }

        def result = projectService.importToProject(
                project,
                framework,
                projectAuthContext,
                stream,
                archiveParams
        )
        switch (respFormat) {
            case 'json':
                render(contentType: 'application/json'){
                    import_status result.success?'successful':'failed'
                    successful result.success
                    if (!result.success) {
                        //list errors
                        errors result.joberrors
                    }

                    if(result.execerrors){
                        execution_errors result.execerrors
                    }

                    if(result.aclerrors){
                        acl_errors result.aclerrors
                    }
                    if(result.scmerrors){
                        scm_errors result.scmerrors
                    }
                    if (request.api_version > ApiVersions.V34) {
                        if (result.importerErrors) {
                            other_errors result.importerErrors
                        }
                    }
                }
                break;
            case 'xml':
                apiService.renderSuccessXml(request, response) {
                    delegate.'import'(status: result.success ? 'successful' : 'failed', successful:result.success){
                        if(!result.success){
                            //list errors
                            delegate.'errors'(count: result.joberrors.size()){
                                result.joberrors.each{
                                    delegate.'error'(it)
                                }
                            }
                        }
                        if(result.execerrors){
                            delegate.'executionErrors'(count: result.execerrors.size()){
                                result.execerrors.each{
                                    delegate.'error'(it)
                                }
                            }
                        }
                        if(result.aclerrors){
                            delegate.'aclErrors'(count: result.aclerrors.size()){
                                result.aclerrors.each{
                                    delegate.'error'(it)
                                }
                            }
                        }
                        if(result.scmerrors){
                            delegate.'scmErrors'(count: result.scmerrors.size()){
                                result.scmerrors.each{
                                    delegate.'error'(it)
                                }
                            }
                        }
                        if(request.api_version> ApiVersions.V34) {
                            if(result.importerErrors){
                                delegate.'otherErrors'(count: result.importerErrors.size()){
                                    result.importerErrors.each{
                                        delegate.'error'(it)
                                    }
                                }
                            }
                        }
                    }
                }
                break;

        }
    }
}
