package org.rundeck.tests.functional.selenium.pages

import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import org.rundeck.util.container.SeleniumContext

class JobListPage extends BasePage {

    By createNewJob = By.linkText("Create a new Job")
    By newJob = By.partialLinkText('New Job')

    String loadPath = "/jobs"

    JobListPage(final SeleniumContext context) {
        super(context)
    }

    void validatePage() {
        if (!driver.currentUrl.contains(loadPath)) {
            throw new IllegalStateException("Not on job list page: " + driver.currentUrl)
        }
    }

    WebElement getCreateJobButton() {
        el createNewJob
    }

    WebElement getNewJobButton() {
        el newJob
    }
}
