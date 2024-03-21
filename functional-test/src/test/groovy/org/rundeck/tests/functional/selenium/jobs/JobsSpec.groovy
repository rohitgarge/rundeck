package org.rundeck.tests.functional.selenium.jobs

import org.rundeck.util.gui.pages.execution.ExecutionShowPage
import org.openqa.selenium.Keys
import org.rundeck.util.gui.pages.execution.ExecutionShowPage
import org.rundeck.util.gui.pages.jobs.JobCreatePage
import org.rundeck.util.gui.pages.jobs.JobListPage
import org.rundeck.util.gui.pages.jobs.JobShowPage
import org.rundeck.util.gui.pages.jobs.JobTab
import org.rundeck.util.gui.pages.jobs.StepType
import org.rundeck.util.gui.pages.login.LoginPage
import org.rundeck.util.gui.pages.profile.UserProfilePage
import org.rundeck.util.annotations.SeleniumCoreTest
import org.rundeck.util.container.SeleniumBase
import org.rundeck.util.gui.pages.project.ActivityPage
import spock.lang.Stepwise

import java.util.stream.Collectors

@SeleniumCoreTest
@Stepwise
class JobsSpec extends SeleniumBase {

    def setupSpec() {
        setupProjectArchiveDirectoryResource(SELENIUM_BASIC_PROJECT, "/projects-import/${SELENIUM_BASIC_PROJECT}")
    }

    def setup() {
        go(LoginPage).login(TEST_USER, TEST_PASS)
    }

    def "change workflow strategy"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
            def jobShowPage = page JobShowPage
        then:
            jobCreatePage.jobNameInput.sendKeys 'jobs workflow strategy'
            jobCreatePage.tab JobTab.WORKFLOW click()
            jobCreatePage.workFlowStrategyField.sendKeys 'Parallel'
            jobCreatePage.waitIgnoringForElementVisible jobCreatePage.strategyPluginParallelField
            jobCreatePage.strategyPluginParallelMsgField.getText() == 'Run all steps in parallel'

            jobCreatePage.executeScript "window.location.hash = '#addnodestep'"
            jobCreatePage.stepLink 'exec-command', StepType.NODE click()
            jobCreatePage.waitForElementVisible jobCreatePage.adhocRemoteStringField
            jobCreatePage.adhocRemoteStringField.click()
            jobCreatePage.waitForNumberOfElementsToBeOne jobCreatePage.floatBy
            jobCreatePage.adhocRemoteStringField.sendKeys 'echo selenium test'
            jobCreatePage.saveStep 0
            jobCreatePage.createJobButton.click()
        expect:
            jobShowPage.jobDefinitionModal.click()
            jobShowPage.workflowDetailField.getText() == 'Parallel Run all steps in parallel'
    }

    def "cancel job create with default lang"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
            def jobListPage = page JobListPage
        then:
            jobCreatePage.cancelButton.click()
        expect:
            jobListPage.validatePage()
    }

    def "change UI lang fr_FR and cancel job create"() {
        setup:
            def userProfilePage = page UserProfilePage
            def jobCreatePage = page JobCreatePage
            def jobListPage = page JobListPage
        when:
            userProfilePage.loadPath += "?lang=fr_FR"
            userProfilePage.go()
            jobCreatePage.loadCreatePath SELENIUM_BASIC_PROJECT
        then:
            userProfilePage.languageLabel.getText() == 'Langue:'
            jobCreatePage.go()
            jobCreatePage.cancelButton.click()
        expect:
            jobListPage.validatePage()
    }

    def "change UI lang ja_JP and cancel job create"() {
        setup:
            def userProfilePage = page UserProfilePage
            def jobCreatePage = page JobCreatePage, SELENIUM_BASIC_PROJECT
            def jobListPage = page JobListPage
        when:
            userProfilePage.loadPath += "?lang=ja_JP"
            userProfilePage.go()
            jobCreatePage.loadCreatePath SELENIUM_BASIC_PROJECT
        then:
            userProfilePage.languageLabel.getText() == '言語:'
            jobCreatePage.go()
            jobCreatePage.cancelButton.click()
        expect:
            jobListPage.validatePage()
    }

    def "Duplicate_options - only validations, not save jobs"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
            def optName = 'test'
        then:
            jobCreatePage.fillBasicJob 'duplicate options'
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 0 sendKeys optName
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.executeScript "window.location.hash = '#workflowKeepGoingFail'"
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 0

            jobCreatePage.duplicateButton optName click()
            jobCreatePage.waitFotOptLi 1

            jobCreatePage.duplicateButton optName click()
            jobCreatePage.waitFotOptLi 2

        expect:
            jobCreatePage.optionNameSaved 1 getText() equals optName + '_1'
            jobCreatePage.optionNameSaved 2 getText() equals optName + '_2'
    }

    def "create job with dispatch to nodes"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
            def jobShowPage = page JobShowPage
        then:
            jobCreatePage.fillBasicJob 'jobs with nodes'
            jobCreatePage.tab JobTab.NODES click()
            jobCreatePage.nodeDispatchTrueCheck.click()
            jobCreatePage.waitForElementVisible jobCreatePage.nodeFilterLinkButton
            jobCreatePage.nodeFilterLinkButton.click()
            jobCreatePage.nodeFilterSelectAllLinkButton.click()
            jobCreatePage.waitForTextToBePresentInElement jobCreatePage.nodeMatchedCountField, '1 Node Matched'
            jobCreatePage.excludeFilterTrueCheck.click()
            jobCreatePage.editableFalseCheck.click()
            jobCreatePage.schedJobNodeThreadCountField.clear()
            jobCreatePage.schedJobNodeThreadCountField.sendKeys '3'
            jobCreatePage.schedJobNodeRankAttributeField.clear()
            jobCreatePage.schedJobNodeRankAttributeField.sendKeys 'arank'
            jobCreatePage.executeScript "window.location.hash = '#nodeRankOrderDescending'"
            jobCreatePage.nodeRankOrderDescendingField.click()
            jobCreatePage.nodeKeepGoingTrueCheck.click()
            jobCreatePage.successOnEmptyNodeFilterTrueCheck.click()
            jobCreatePage.nodesSelectedByDefaultFalseCheck.click()
            jobCreatePage.createJobButton.click()
            jobShowPage.jobDefinitionModal.click()
        expect:
            jobShowPage.nodeFilterSectionMatchedNodesLabel.getText() == 'Include nodes matching: name: .*'
            jobShowPage.threadCountLabel.getText() == 'Execute on up to 3 Nodes at a time.'
            jobShowPage.nodeKeepGoingLabel.getText() == 'If a node fails: Continue running on any remaining nodes before failing the step.'
            jobShowPage.nodeRankOrderAscendingLabel.getText() == 'Sort nodes by arank in descending order.'
            jobShowPage.nodeSelectedByDefaultLabel.getText() == 'Node selection: The user has to explicitly select target nodes'
    }

    def "rename job with orchestrator"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
            def jobShowPage = page JobShowPage
        then:
            jobCreatePage.fillBasicJob 'job with node orchestrator'
            jobCreatePage.tab JobTab.NODES click()
            jobCreatePage.nodeDispatchTrueCheck.click()
            jobCreatePage.waitForElementVisible jobCreatePage.nodeFilterLinkButton
            jobCreatePage.nodeFilterLinkButton.click()
            jobCreatePage.nodeFilterSelectAllLinkButton.click()
            jobCreatePage.waitForTextToBePresentInElement jobCreatePage.nodeMatchedCountField, '1 Node Matched'
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.orchestratorDropdownButton
            jobCreatePage.waitForElementToBeClickable jobCreatePage.orchestratorDropdownButton
            jobCreatePage.orchestratorDropdownButton.click()
            jobCreatePage.orchestratorChoiceLink 'rankTiered' click()
            jobCreatePage.createJobButton.click()
            jobShowPage.jobDefinitionModal.click()
        expect:
            jobShowPage.orchestratorNameLabel.getText() == 'Rank Tiered'
            jobShowPage.closeDefinitionModalButton.click()
            jobShowPage.jobActionDropdownButton.click()
            jobShowPage.editJobLink.click()
            jobCreatePage.jobNameInput.clear()
            jobCreatePage.jobNameInput.sendKeys 'renamed job with node orchestrator'
            jobCreatePage.tab JobTab.NODES click()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.updateJobButton
            jobCreatePage.updateJobButton.click()
            jobShowPage.jobLinkTitleLabel.getText() == 'renamed job with node orchestrator'
            jobShowPage.jobDefinitionModal.click()
            jobShowPage.orchestratorNameLabel.getText() == 'Rank Tiered'
    }

    def "job options config - check usage session"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.fillBasicJob 'a job with options'
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 0 sendKeys 'seleniumOption1'
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.sessionSectionLabel
            jobCreatePage.sessionSectionLabel.isDisplayed()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.saveOptionButton
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 0
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.createJobButton
            jobCreatePage.createJobButton.click()
    }

    def "job options config - check storage session"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.fillBasicJob 'a job with option secure'
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 0 sendKeys 'seleniumOption1'
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.sessionSectionLabel.isDisplayed()
            jobCreatePage.secureInputTypeRadio.click()
            jobCreatePage.optionOpenKeyStorageButton.click()
            jobCreatePage.optionCloseKeyStorageButton.click()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.saveOptionButton
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 0
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.createJobButton
            jobCreatePage.createJobButton.click()
    }

    def "job option simple redo"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.fillBasicJob 'a job with options undo test'
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 0 sendKeys 'seleniumOption1'
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.sessionSectionLabel.isDisplayed()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.saveOptionButton
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 0
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 1 sendKeys 'seleniumOption2'
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.sessionSectionLabel.isDisplayed()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.saveOptionButton
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 1
            jobCreatePage.waitForElementAttributeToChange jobCreatePage.optionUndoButton, 'disabled', null
            jobCreatePage.optionUndoButton.click()
        expect:
            jobCreatePage.waitForOptionsToBe 1, 0
            jobCreatePage.optionLis 1 isEmpty()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.createJobButton
            jobCreatePage.createJobButton.click()
    }

    def "No default value field shown in secure job option section"() {
        when:
        def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
        jobCreatePage.fillBasicJob 'a job with option secure'
        jobCreatePage.optionButton.click()
        jobCreatePage.optionName 0 sendKeys 'seleniumOption1'
        jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
        jobCreatePage.sessionSectionLabel.isDisplayed()
        jobCreatePage.secureInputTypeRadio.click()
        jobCreatePage.storagePathInput.sendKeys("test")
        jobCreatePage.secureInputTypeRadio.click()
        jobCreatePage.storagePathInput.clear()
        jobCreatePage.secureInputTypeRadio.click()

        expect:
        !jobCreatePage.defaultValueInput.isDisplayed()
    }

    def "job option revert all"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.fillBasicJob 'a job with options revert all test'
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 0 sendKeys 'seleniumOption1'
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.sessionSectionLabel.isDisplayed()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.saveOptionButton
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 0
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 1 sendKeys 'seleniumOption2'
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.sessionSectionLabel.isDisplayed()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.saveOptionButton
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 1
            jobCreatePage.executeScript "window.location.hash = '#optundoredo'"
            jobCreatePage.waitForElementAttributeToChange jobCreatePage.optionUndoButton, 'disabled', null
            jobCreatePage.optionUndoButton
            jobCreatePage.optionRevertAllButton.click()
            jobCreatePage.optionConfirmRevertAllButton.click()
        expect:
            jobCreatePage.waitForOptionsToBe 0, 0
            jobCreatePage.waitForOptionsToBe 1, 0
            jobCreatePage.optionLis 0 isEmpty()
            jobCreatePage.optionLis 1 isEmpty()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.createJobButton
            jobCreatePage.createJobButton.click()
    }

    def "job option undo redo"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.fillBasicJob 'a job with options undo-redo test'
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 0 sendKeys 'seleniumOption1'
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.sessionSectionLabel.isDisplayed()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.saveOptionButton
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 0
            jobCreatePage.optionButton.click()
            jobCreatePage.optionName 1 sendKeys 'seleniumOption2'
            jobCreatePage.waitForElementVisible jobCreatePage.separatorOption
            jobCreatePage.sessionSectionLabel.isDisplayed()
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.saveOptionButton
            jobCreatePage.saveOptionButton.click()
            jobCreatePage.waitFotOptLi 1
            jobCreatePage.executeScript "window.location.hash = '#optundoredo'"
            jobCreatePage.waitForElementAttributeToChange jobCreatePage.optionUndoButton, 'disabled', null
            jobCreatePage.optionUndoButton.click()
            jobCreatePage.waitForElementToBeClickable jobCreatePage.optionRedoButton
            sleep 1000
            jobCreatePage.optionRedoButton.click()
        expect:
            !(jobCreatePage.optionLis 0 isEmpty())
            !(jobCreatePage.optionLis 1 isEmpty())
            jobCreatePage.executeScript "arguments[0].scrollIntoView(true);", jobCreatePage.createJobButton
            jobCreatePage.createJobButton.click()
    }

    def "job workflow step context variables autocomplete"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.jobNameInput.sendKeys 'job workflow step context variables autocomplete'
            jobCreatePage.tab JobTab.WORKFLOW click()
            jobCreatePage.executeScript "window.location.hash = '#addnodestep'"
            jobCreatePage.stepLink 'com.batix.rundeck.plugins.AnsiblePlaybookInlineWorkflowStep', StepType.WORKFLOW click()
            jobCreatePage.ansibleBinariesPathField.clear()
            jobCreatePage.ansibleBinariesPathField.sendKeys '${job.id'
            jobCreatePage.autocompleteSuggestions.click()
            jobCreatePage.saveStep 0
            jobCreatePage.createJobButton.click()
        expect:
            def jobShowPage = page JobShowPage
            jobShowPage.jobDefinitionModal.click()
            jobShowPage.autocompleteJobStepDefinitionLabel.getText() == '${job.id}'
    }

    def "job workflow simple undo"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.fillBasicJob 'a job with workflow undo test'
            jobCreatePage.addSimpleCommandStepButton.click()
            jobCreatePage.addSimpleCommandStep 'echo selenium test 2', 1
            jobCreatePage.wfUndoButton.click()
            jobCreatePage.waitForNumberOfElementsToBe jobCreatePage.listWorkFlowItemBy, 1
        expect:
            jobCreatePage.workFlowList.size() == 1
            jobCreatePage.createJobButton.click()
    }

    def "job workflow undo redo"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.fillBasicJob 'a job with workflow undo-redo test'
            jobCreatePage.addSimpleCommandStepButton.click()
            jobCreatePage.addSimpleCommandStep 'echo selenium test 2', 1
            jobCreatePage.waitForElementToBeClickable jobCreatePage.wfUndoButtonLink
            jobCreatePage.wfUndoButtonLink.click()
            jobCreatePage.waitForElementToBeClickable jobCreatePage.wfRedoButtonLink
            jobCreatePage.wfRedoButtonLink.click()
            jobCreatePage.waitForNumberOfElementsToBe jobCreatePage.listWorkFlowItemBy, 2
        expect:
            jobCreatePage.workFlowList.size() == 2
            jobCreatePage.createJobButton.click()
    }

    def "job workflow revert all"() {
        when:
            def jobCreatePage = go JobCreatePage, SELENIUM_BASIC_PROJECT
        then:
            jobCreatePage.fillBasicJob 'a job with workflow revert all test'
            jobCreatePage.addSimpleCommandStepButton.click()
            jobCreatePage.addSimpleCommandStep 'echo selenium test 2', 1
            jobCreatePage.wfRevertAllButton.click()
            jobCreatePage.revertWfConfirmYes.click()
            jobCreatePage.waitForNumberOfElementsToBe jobCreatePage.listWorkFlowItemBy, 0
        expect:
            jobCreatePage.workFlowList.size() == 0
            jobCreatePage.addSimpleCommandStepButton.click()
            jobCreatePage.addSimpleCommandStep 'echo selenium test', 0
            jobCreatePage.createJobButton.click()
    }

    def "job timeout should finish job with timeout status and step marked as failed"() {
        setup:
        final String projectName = 'JobTimeOutTest'
        setupProjectArchiveDirectoryResource(projectName, "/projects-import/${projectName}.rdproject")
        JobShowPage jobPage = page(JobShowPage, projectName).forJob('1032a729-c251-4940-86b5-20f99cb5e769')
        jobPage.go()

        when:
        ExecutionShowPage executionPage = jobPage.runJob(true)

        then:
        noExceptionThrown()
        verifyAll {
            executionPage.getExecutionStatus() == 'TIMEDOUT'
            executionPage.getNodesView().expandNode(0).getExecStateForSteps() == ['SUCCEEDED', 'FAILED']
        }

    }

        def "Run job later"() {
            given:
            def projectName = "run-job-later-test"
            JobCreatePage jobCreatePage = page JobCreatePage
            JobShowPage jobShowPage = page JobShowPage
            ActivityPage activityPage = page ActivityPage
            ExecutionShowPage executionShowPage = page ExecutionShowPage

            when:
            setupProject(projectName)
            go JobCreatePage, projectName
            jobCreatePage.fillBasicJob 'Run job later'
            jobCreatePage.addSimpleCommandStepButton.click()
            jobCreatePage.addSimpleCommandStep 'echo asd', 1
            jobCreatePage.createJobButton.click()
            jobShowPage.validatePage()
            jobShowPage.executionOptionsDropdown.click()
            jobShowPage.runJobLaterOption.click()
            jobShowPage.runJobLaterMinuteArrowUp.click()
            jobShowPage.runJobLaterCreateScheduleButton.click()
            executionShowPage.waitForElementVisible(executionShowPage.jobRunSpinner)
            executionShowPage.waitUntilSpinnerHides()
            executionShowPage.waitForElementVisible(executionShowPage.nodeFlowState)
            activityPage.loadActivityPageForProject(projectName)
            activityPage.go()
            def projectExecutions = Integer.parseInt(activityPage.executionCount.text)

            then:
            projectExecutions > 0

            cleanup:
            deleteProject(projectName)
        }
    }

    def "Filter steps"(){
        given:
        def projectName = "filter-steps-later-test"
        JobCreatePage jobCreatePage = page JobCreatePage
        JobShowPage jobShowPage = page JobShowPage

        when:
        setupProject(projectName)
        go JobCreatePage, projectName
        jobCreatePage.jobNameInput.sendKeys("test")
        jobCreatePage.tab(JobTab.WORKFLOW).click()
        jobCreatePage.waitForElementToBeClickable(jobCreatePage.stepFilterInput)
        jobCreatePage.stepFilterInput.sendKeys("cmd")
        jobCreatePage.stepFilterSearchButton.click()

        then: "Command step is not visible, since the list dont have any steps"
        !jobCreatePage.commandStepVisible()

        when: "We provide a valid filter"
        jobCreatePage.stepFilterInput.sendKeys(Keys.chord(Keys.CONTROL, "a"))
        jobCreatePage.stepFilterInput.sendKeys(Keys.BACK_SPACE)
        jobCreatePage.stepFilterInput.sendKeys("command")
        jobCreatePage.stepFilterSearchButton.click()

        then: "We can create the command step"
        jobCreatePage.addSimpleCommandStep("echo 'asd'", 0)
        jobCreatePage.createJobButton.click()
        jobShowPage.waitForElementVisible(jobShowPage.jobUuid)
        jobShowPage.validatePage()

        cleanup:
        deleteProject(projectName)

    }

    def "Select all json list options by default"(){
        given:
        def projectName = "select-all-json-test"
        def optionListOfNames = "names"
        def optionListOfValues = "search"
        JobCreatePage jobCreatePage = page JobCreatePage
        JobShowPage jobShowPage = page JobShowPage

        when:
        setupProject(projectName)
        go JobCreatePage, projectName
        jobCreatePage.jobNameInput.sendKeys("test")
        jobCreatePage.tab(JobTab.WORKFLOW).click()
        jobCreatePage.optionButton.click()
        jobCreatePage.optionName(0).sendKeys(optionListOfNames)
        jobCreatePage.jobOptionListValueInput.sendKeys("option1,option2,option3,option4")
        jobCreatePage.jobOptionListDelimiter.sendKeys(",")
        jobCreatePage.jobOptionEnforcedInput.click()
        jobCreatePage.saveOptionButton.click()

        jobCreatePage.optionButton.click()
        jobCreatePage.optionName(1).sendKeys(optionListOfValues)
        jobCreatePage.jobOptionAllowedValuesRemoteUrlInput.click()
        jobCreatePage.jobOptionAllowedValuesRemoteUrlValueTextInput.sendKeys("file:/home/\${option.names.value}/saved_searches.json")
        jobCreatePage.jobOptionEnforcedInput.click()
        jobCreatePage.jobOptionRequiredInput.click()
        jobCreatePage.jobOptionMultiValuedInput.click()
        jobCreatePage.waitForElementVisible(jobCreatePage.jobOptionMultivaluedDelimiterBy)
        jobCreatePage.jobOptionMultivaluedDelimiter.sendKeys(",")
        jobCreatePage.jobOptionMultiValuedAllSelectedInput.click()
        jobCreatePage.saveOptionButton.click()

        jobCreatePage.addSimpleCommandStep("echo 'asd'", 0)
        jobCreatePage.createJobButton.click()
        def jobUuid = jobShowPage.jobUuid.text
        jobShowPage.goToJob(jobUuid)

        jobShowPage.waitForElementVisible(jobShowPage.getOptionSelectByName(optionListOfNames))

        jobShowPage.selectOptionFromOptionListByName(optionListOfNames, selection)
        def searchListValues = jobShowPage.getOptionSelectChildren(optionListOfValues)
        def flag = true
        searchListValues.stream().forEach {
            if( !it.isSelected() ) false
        }
        noUnselectedOptions = flag

        then:
        jobShowPage.validatePage()

        cleanup:
        deleteProject(projectName)

        where:
        selection | noUnselectedOptions
        2         | true
        3         | true
        4         | true

    }

    def "Step duplication"(){
        given:
        def projectName = "step-duplication-test"
        JobCreatePage jobCreatePage = page JobCreatePage
        JobShowPage jobShowPage = page JobShowPage
        ExecutionShowPage executionShowPage = page ExecutionShowPage

        when:
        setupProject(projectName)
        go JobCreatePage, projectName
        jobCreatePage.jobNameInput.sendKeys("test-duplication")
        jobCreatePage.tab(JobTab.WORKFLOW).click()
        jobCreatePage.addSimpleCommandStep "echo 'This is a simple job'", 0
        jobCreatePage.createJobButton.click()
        jobShowPage.waitForElementVisible(jobShowPage.jobActionDropdownButton)
        jobShowPage.jobActionDropdownButton.click()
        jobShowPage.waitForElementToBeClickable(jobShowPage.editJobLink)
        jobShowPage.editJobLink.click()
        jobCreatePage.waitForElementVisible(jobCreatePage.tab(JobTab.WORKFLOW))
        jobCreatePage.tab(JobTab.WORKFLOW).click()
        jobCreatePage.duplicateWfStepButton.click()
        jobCreatePage.waitForElementVisible(jobCreatePage.getWfStepByListPosition(1))
        jobCreatePage.updateBtn.click()
        jobShowPage.waitForElementVisible(jobShowPage.jobUuid)
        jobShowPage.runJob(true)
        executionShowPage.viewButtonOutput.click()
        def logLines = executionShowPage.logOutput.stream().map {
            it.text
        }.collect(Collectors.toList())

        then:
        logLines.size() == 2
        logLines.forEach {
            it == 'This is a simple job'
        }

        cleanup:
        deleteProject(projectName)

    }

    def "Url job options"(){
        given:
        def projectName = "url-job-options-test"
        JobCreatePage jobCreatePage = page JobCreatePage
        JobShowPage jobShowPage = page JobShowPage
        ExecutionShowPage executionShowPage = page ExecutionShowPage

        when:
        setupProject(projectName)
        go JobCreatePage, projectName
        jobCreatePage.jobNameInput.sendKeys("test-url-opts")
        jobCreatePage.tab(JobTab.WORKFLOW).click()
        jobCreatePage.optionButton.click()
        jobCreatePage.optionName(0).sendKeys("remote")
        jobCreatePage.scrollToElement(jobCreatePage.jobOptionAllowedValuesRemoteUrlInput)
        jobCreatePage.jobOptionAllowedValuesRemoteUrlInput.click()
        jobCreatePage.jobOptionAllowedValuesRemoteUrlValueTextInput.sendKeys("https://httpbin.org/stream/4")
        jobCreatePage.waitForElementVisible(jobCreatePage.saveOptionButton)
        jobCreatePage.scrollToElement(jobCreatePage.saveOptionButton)
        jobCreatePage.saveOptionButton.click()
        jobCreatePage.addSimpleCommandStep "echo 'This is a simple job'", 0
        jobCreatePage.createJobButton.click()
        jobShowPage.waitForElementVisible(jobShowPage.jobUuid)
        jobShowPage.goToJob(jobShowPage.jobUuid.text)
        jobShowPage.waitForElementVisible(jobShowPage.getJobOptionValueListItem("url"))
        jobShowPage.getJobOptionValueListItem("url").click()
        def optionValueSelected = jobShowPage.jobOptionValueInput.getAttribute("value")
        jobShowPage.runJob(true)
        def optionValueExecuted = executionShowPage.optionValueSelected.text

        then:
        optionValueExecuted == optionValueSelected

        cleanup:
        deleteProject(projectName)

    }

}
