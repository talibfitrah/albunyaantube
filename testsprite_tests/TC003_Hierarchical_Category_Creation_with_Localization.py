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
        # -> Click on 'Categories' link to navigate to category management page
        frame = context.pages[-1]
        # Click on 'Categories' link in the sidebar to go to category management page 
        elem = frame.locator('xpath=html/body/div/div/aside/nav/a[3]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        # -> Click 'Add Category' button to start creating a top-level category
        frame = context.pages[-1]
        # Click 'Add Category' button to create a new top-level category 
        elem = frame.locator('xpath=html/body/div/div/div/main/div/header/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        # -> Input the top-level category name in English locale in the 'Category Name' field
        frame = context.pages[-1]
        # Input top-level category name in English locale 
        elem = frame.locator('xpath=html/body/div[2]/div/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('Top Level Category EN')
        frame = context.pages[-1]
        # Click Save button to save the top-level category 
        elem = frame.locator('xpath=html/body/div[2]/div/form/div[4]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000) 
        # -> Edit the created top-level category to add Arabic and Dutch localized names
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/header/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Input Arabic localized name in the Category Name field
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('الفئة العليا')
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div[4]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Edit the top-level category to add Dutch localized name
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/div[2]/div/div[3]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Input Dutch localized name in the Category Name field and save
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('Bovenste Categorie')
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div[4]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Create a child category under the top-level category with localized names in English, Arabic, and Dutch
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/div[2]/div/div[3]/button').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Input English localized name for the child category
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('Child Category EN')
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div[5]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Edit the child category to add Arabic localized name
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/div[2]/div[2]/div/div/div[3]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Input Arabic localized name for the child category and save
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('الفئة الفرعية')
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div[5]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Edit the child category to add Dutch localized name
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div/div/div/main/div/div/div/div[3]/div/div[3]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # -> Input Dutch localized name for the child category and save
        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div/input').nth(0)
        await page.wait_for_timeout(3000); await elem.fill('Subcategorie')
        

        frame = context.pages[-1]
        elem = frame.locator('xpath=html/body/div[2]/div/form/div[4]/button[2]').nth(0)
        await page.wait_for_timeout(3000); await elem.click(timeout=5000)
        

        # --> Assertions to verify final state
        frame = context.pages[-1]
        try:
            await expect(frame.locator('text=Category Creation Successful').first).to_be_visible(timeout=1000)
        except AssertionError:
            raise AssertionError('Test case failed: The hierarchical categories with correct parent-child structure and localized names for English, Arabic, and Dutch were not created successfully as per the test plan.')
        await asyncio.sleep(5)

    finally:
        if context:
            await context.close()
        if browser:
            await browser.close()
        if pw:
            await pw.stop()

asyncio.run(run_test())
    