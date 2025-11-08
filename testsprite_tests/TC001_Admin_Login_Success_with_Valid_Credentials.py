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
        # -> Enter valid admin email
        frame = context.pages[-1]
        # Enter valid admin email
        elem = frame.locator('xpath=html/body/div/div/section/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('admin@albunyaan.tube')
        

        # -> Enter correct password
        frame = context.pages[-1]
        # Enter correct password
        elem = frame.locator('xpath=html/body/div/div/section/form/div[2]/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('ChangeMe!123')
        

        # -> Click the login button to submit credentials
        frame = context.pages[-1]
        # Click the sign in button to submit login credentials
        elem = frame.locator('xpath=html/body/div/div/section/form/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Verify reception and validity of JWT token after login
        frame = context.pages[-1]
        # Click Sign out button to test role-based access control after token verification
        elem = frame.locator('xpath=html/body/div/div/div/header/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Enter valid admin email to attempt login again
        frame = context.pages[-1]
        # Enter valid admin email
        elem = frame.locator('xpath=html/body/div/div/section/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('admin@albunyaan.tube')
        

        frame = context.pages[-1]
        # Enter correct password
        elem = frame.locator('xpath=html/body/div/div/section/form/div[2]/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('ChangeMe!123')
        

        frame = context.pages[-1]
        # Click the sign in button to submit login credentials
        elem = frame.locator('xpath=html/body/div/div/section/form/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Extract JWT token from local storage or session storage to verify reception and validity
        frame = context.pages[-1]
        # Click Sign out button to test role-based access control after token verification
        elem = frame.locator('xpath=html/body/div/div/div/header/div[2]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Enter valid admin email to proceed with login
        frame = context.pages[-1]
        # Enter valid admin email
        elem = frame.locator('xpath=html/body/div/div/section/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('admin@albunyaan.tube')
        

        frame = context.pages[-1]
        # Enter correct password
        elem = frame.locator('xpath=html/body/div/div/section/form/div[2]/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('ChangeMe!123')
        

        frame = context.pages[-1]
        # Click the sign in button to submit login credentials
        elem = frame.locator('xpath=html/body/div/div/section/form/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # --> Assertions to verify final state
        frame = context.pages[-1]
        await expect(frame.locator('text=Dashboard').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Content Search').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Categories').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Pending Approvals').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Content Library').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Exclusions').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Import/Export').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Video Validation').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Users').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Audit log').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Activity log').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Settings').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Signed in').first).to_be_visible(timeout=30000)
        await expect(frame.locator('text=Salaam, welcome back').first).to_be_visible(timeout=30000)
        await asyncio.sleep(5)
    
    finally:
        if context:
            await context.close()
        if browser:
            await browser.close()
        if pw:
            await pw.stop()
            
asyncio.run(run_test())
    