import asyncio
from playwright import async_api
from playwright.async_api import expect

async def run_test():
    pw = None
    browser = None
    context = None

    try:
        # Start a Playwright session in asynchronous mode
        pw = await async_api.async_playwright().start()

        # Launch a Chromium browser in headless mode with custom arguments
        browser = await pw.chromium.launch(
            headless=True,
            args=[
                "--window-size=1280,720",         # Set the browser window size
                "--disable-dev-shm-usage",        # Avoid using /dev/shm which can cause issues in containers
                "--ipc=host",                     # Use host-level IPC for better stability
                "--single-process"                # Run the browser in a single process mode
            ],
        )

        # Create a new browser context (like an incognito window)
        context = await browser.new_context()
        context.set_default_timeout(5000)

        # Open a new page in the browser context
        page = await context.new_page()

        # Navigate to your target URL and wait until the network request is committed
        await page.goto("http://localhost:5173", wait_until="commit", timeout=10000)

        # Wait for the main page to reach DOMContentLoaded state (optional for stability)
        try:
            await page.wait_for_load_state("domcontentloaded", timeout=3000)
        except async_api.Error:
            pass

        # Iterate through all iframes and wait for them to load as well
        for frame in page.frames:
            try:
                await frame.wait_for_load_state("domcontentloaded", timeout=3000)
            except async_api.Error:
                pass

        # Interact with the page elements to simulate user flow
        # -> Input admin credentials and sign in
        frame = context.pages[-1]
        # Input admin email 
        elem = frame.locator('xpath=html/body/div/div/section/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('admin@albunyaan.tube')
        frame = context.pages[-1]
        # Input admin password 
        elem = frame.locator('xpath=html/body/div/div/section/form/div[2]/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('ChangeMe!123')
        frame = context.pages[-1]
        # Click Sign in button 
        elem = frame.locator('xpath=html/body/div/div/section/form/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        # -> Click on 'Content Search' to start searching for YouTube content
        frame = context.pages[-1]
        # Click on 'Content Search' in the navigation menu 
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        # -> Input a YouTube channel name in the search box and click Search
        frame = context.pages[-1]
        # Input YouTube channel name in search box 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('test channel')
        frame = context.pages[-1]
        # Click Search button to search for the channel 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000) 
        # -> Click 'Add for Approval' button for the first channel in the search results to start the approval workflow.
        frame = context.pages[-1]
        # Add for Approval button for the first channel
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div/div/img').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Try clicking 'Add for Approval' button for the second channel in the search results to see if the approval workflow starts.
        frame = context.pages[-1]
        # Add for Approval button for the second channel
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div[2]/div[3]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Navigate to 'Categories' tab to verify if categories exist or create new categories to enable assignment.
        frame = context.pages[-1]
        # Categories tab
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div[4]/div[3]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Create a new category in the 'Categories' tab to enable assignment in the approval workflow.
        frame = context.pages[-1]
        # Categories tab
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[3]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        # Add new category button
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div[10]/div/img').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Return to 'Content Search' or 'Pending Approvals' to retry adding a YouTube channel for approval and assign one of the existing categories.
        frame = context.pages[-1]
        # Content Search tab
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Input a YouTube channel name in the search box and click the Search button to get search results.
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('test channel')
        

        frame = context.pages[-1]
        # Search button
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Click 'Add for Approval' button for the first channel in the search results to start the approval workflow.
        frame = context.pages[-1]
        # Add for Approval button for the first channel
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div/div[3]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Navigate to 'Categories' tab to verify or create categories to enable assignment in the approval workflow.
        frame = context.pages[-1]
        # Categories tab
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div[5]/div/img').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Try to close the 'Assign Categories' modal by clicking the 'Close' button to regain access to the main interface and then navigate to 'Categories' tab to create categories.
        frame = context.pages[-1]
        # Close button on 'Assign Categories' modal
        elem = frame.locator('xpath=html/body/div[2]/div/div/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Click 'Categories' tab to verify categories exist or create new categories before retrying the approval workflow.
        frame = context.pages[-1]
        # Categories tab
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[3]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Navigate to Content Search tab to search for a YouTube channel and add it for approval.
        frame = context.pages[-1]
        # Content Search tab
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # --> Assertions to verify final state
        frame = context.pages[-1]
        try:
            await expect(frame.locator('text=Approval Completed Successfully').first).to_be_visible(timeout=1000)
        except AssertionError:
            raise AssertionError("Test case failed: The approval workflow did not start as expected with status 'Add for Approval'. The item status 'Add for Approval' was not found in the approval queue after adding YouTube content.")
        await asyncio.sleep(5)

    finally:
        if context:
            await context.close()
        if browser:
            await browser.close()
        if pw:
            await pw.stop()

asyncio.run(run_test())
    