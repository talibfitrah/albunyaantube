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
        # -> Navigate to Content Search to test fetching YouTube video metadata
        frame = context.pages[-1]
        # Click Content Search in the sidebar to test YouTube video metadata fetching 
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        # -> Input a valid YouTube video ID in the search input and search
        frame = context.pages[-1]
        # Input a valid YouTube video ID 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('dQw4w9WgXcQ')
        frame = context.pages[-1]
        # Click Search button to fetch video metadata 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000) 
        # -> Input a deleted or private YouTube video ID in the search input and perform search
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('INVALID_OR_PRIVATE_VIDEO_ID')
        

        # -> Check the page for any error messages or UI indicators about invalid or unavailable video and verify if the 'Add for Approval' button is disabled or missing for the invalid video ID
        await page.mouse.wheel(0, await page.evaluate('() => window.innerHeight'))
        

        # -> Check if clicking 'Add for Approval' on an invalid video triggers any error or prevents addition
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div/div[3]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Attempt to assign categories and finalize adding the invalid/private video to check if backend validation prevents addition
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/div[3]/div/div[14]/div/label/input').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/div[4]/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Try to finalize adding an invalid/private video by assigning categories and submitting to check if backend validation prevents addition
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div/div[3]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Select a category checkbox and click 'Assign' to finalize adding the invalid/private video and observe if any error or rejection occurs
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/div[3]/div/div/div/label/input').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/div[4]/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Select a category checkbox and click 'Assign' to finalize adding the invalid/private video and observe if any error or rejection occurs
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div[41]/div/img').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Select a category and click 'Assign' to finalize adding the invalid/private video and observe if any error or rejection occurs
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div[3]/div[2]/div[41]/div/img').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # --> Assertions to verify final state
        frame = context.pages[-1]
        await expect(frame.locator('text=Add for Approval').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=I Rick Rolled an Entire City').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=RickRolled by an Ad...').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=The Smartest RickRoll').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=I rickrolled my class and this happened').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Content Search').first).to_be_visible(timeout=30000)
        await asyncio.sleep(5)

    finally:
        if context:
            await context.close()
        if browser:
            await browser.close()
        if pw:
            await pw.stop()

asyncio.run(run_test())
    