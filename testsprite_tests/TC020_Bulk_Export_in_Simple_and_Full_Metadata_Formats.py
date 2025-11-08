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
        # -> Input email and password, then click Sign in
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
        # -> Click on 'Import/Export' menu item to access export options
        frame = context.pages[-1]
        # Click on Import/Export menu item 
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[7]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        # -> Select Simple Format, ensure only Include Channels is checked, then click Download JSON to export simple format channels
        frame = context.pages[-1]
        # Select Simple Format option 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section/div[2]/label').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        frame = context.pages[-1]
        # Uncheck Include Playlists checkbox 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        frame = context.pages[-1]
        # Uncheck Include Videos checkbox 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label[3]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        frame = context.pages[-1]
        # Click Download JSON button to export channels in Simple format 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000) 
        # -> Reattempt to export simple format channels and capture the actual JSON content with channel IDs
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section/div[2]/label').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label/input').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label[3]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Select Simple Format, check only Include Channels, uncheck others, then download JSON and extract content to verify channel IDs only
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section/div[2]/label').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label/input').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label[2]/input').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label[3]/input').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Try to locate or intercept the actual downloaded JSON file content for the simple format export to verify it contains only channel IDs
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[7]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Switch to Full Format export, select Include Channels, then download JSON and extract content to verify full metadata export
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section/div[2]/label[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label/input').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label[4]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Attempt to intercept network requests or responses to capture the actual exported JSON file content for verification
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[7]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Attempt to extract any visible JSON content or logs on the page that might contain the actual exported data, or try to trigger another export with minimal options to isolate data.
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Try to uncheck 'Include Videos' and 'Include Categories', keep only 'Include Channels' checked, select Simple Format, then download JSON and attempt to extract content again to isolate channel IDs in simple format
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section/div[2]/label').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/div/div/label').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[2]/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/section[3]/div[2]/div[2]/div/label').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # --> Assertions to verify final state
        frame = context.pages[-1]
        try:
            await expect(frame.locator('text=Bulk export completed successfully').first).to_be_visible(timeout=1000)
        except AssertionError:
            raise AssertionError("Test case failed: Bulk export of channels in both simple and full metadata formats did not return correctly structured JSON files without data loss as per the test plan.")
        await asyncio.sleep(5)

    finally:
        if context:
            await context.close()
        if browser:
            await browser.close()
        if pw:
            await pw.stop()

asyncio.run(run_test())
    