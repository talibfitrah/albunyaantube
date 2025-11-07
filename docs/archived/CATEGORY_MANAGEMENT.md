# Category Management Guide

## Overview

The Categories page supports **hierarchical category management** with parent-child relationships. Categories are displayed in an expandable tree structure.

## UI Features

### Category Tree Display

The category tree shows:
- **📖 Icon** - Emoji representing the category
- **Name** - Category display name
- **Count** - Number of subcategories (in parentheses)
- **▶ Expand/Collapse** - Click arrow to show/hide subcategories
- **Action Buttons** (appear on hover):
  - **+ Add Subcategory** - Create a child category
  - **✎ Edit** - Modify category details
  - **× Delete** - Remove category (only if no subcategories)

### Tree Structure Example

```
📖 Qur'an Recitation (3)          [+ ✎ ×]
  🌱 Beginner Qur'an              [+ ✎ ×]
  🎵 Tajweed Rules                [+ ✎ ×]
  🧠 Hifdh & Memorization         [+ ✎ ×]

📿 Hadith Collections (1)          [+ ✎ ×]
  4️⃣0️⃣ Forty Hadith               [+ ✎ ×]

💡 Tafsir & Explanation (1)        [+ ✎ ×]
  ✨ Tafsir Bites                 [+ ✎ ×]
```

## How to Use

### Add a Top-Level Category

1. Click **"Add Category"** button (top right)
2. Fill in the form:
   - **Category Name** (required) - e.g., "Fiqh & Practical Rulings"
   - **Icon** (optional) - Emoji like ⚖️
   - **Display Order** - Number for sorting (0 = first)
3. Click **"Save"**

### Add a Subcategory

**Method 1: Using the + Button**
1. Hover over the parent category
2. Click the **+ (plus)** button
3. Fill in subcategory details
4. Click "Save"

**Method 2: From the Dialog**
1. Click **"Add Category"**
2. The form will show **"Parent Category"** field if you clicked + on a category
3. Fill in details
4. Click "Save"

### Edit a Category

1. Hover over the category
2. Click the **✎ (edit)** button
3. Modify:
   - Name
   - Icon
   - Display Order
   - ⚠️ **Note**: Cannot change parent after creation
4. Click "Save"

### Delete a Category

1. Hover over the category
2. Click the **× (delete)** button
3. Confirm deletion

⚠️ **Restriction**: Cannot delete a category that has subcategories. Delete all children first.

## Backend Support

### Endpoints

- `GET /api/admin/categories` - Get all categories (flat list)
- `GET /api/admin/categories/top-level` - Get only top-level categories
- `GET /api/admin/categories/{id}/subcategories` - Get subcategories of a parent
- `POST /api/admin/categories` - Create new category
- `PUT /api/admin/categories/{id}` - Update category
- `DELETE /api/admin/categories/{id}` - Delete category (fails if has children)

### Database Fields

```typescript
{
  id: string;                    // Firestore document ID
  name: string;                  // Display name
  parentCategoryId: string | null; // Parent ID (null for top-level)
  icon: string;                  // Emoji icon
  displayOrder: number;          // Sort order
  localizedNames: {              // Translations
    en: string;
    ar?: string;
  };
  createdAt: Timestamp;
  updatedAt: Timestamp;
  createdBy: string;
  updatedBy: string;
}
```

## Current Seeded Data

The system is pre-seeded with 19 categories:

### Top-Level Categories (9)
1. 📖 Qur'an Recitation
2. 📿 Hadith Collections
3. 🕌 Seerah & Prophetic Biography
4. 💡 Tafsir & Explanation
5. 🔔 Aqeedah & Creed
6. ⚖️ Fiqh & Practical Rulings
7. 🧒 Kids Corner
8. 👥 Youth Programs
9. 🔤 Arabic Language
10. 🎤 Nasheeds (Voice Only)
11. 🌙 Everyday Muslim Life
12. 📜 Islamic History
13. 🤝 New Muslim Support
14. 🧘 Wellness & Mindfulness

### Subcategories (5)
- Under "Qur'an Recitation":
  - 🌱 Beginner Qur'an
  - 🎵 Tajweed Rules
  - 🧠 Hifdh & Memorization
- Under "Hadith Collections":
  - 4️⃣0️⃣ Forty Hadith
- Under "Tafsir & Explanation":
  - ✨ Tafsir Bites

## Troubleshooting

### "Duplicate" Categories
If you see multiple categories with similar names (e.g., multiple "Quran"), they are likely:
- A parent category **and** its subcategories displayed flat
- Click the ▶ arrow to expand and see the hierarchy

### Icons Not Showing
- Ensure icons are emoji characters (not URLs)
- If you see URLs like `https://via.placeholder.com/...`, re-run the seeder with emoji icons

### Can't Add Subcategory
- Verify the parent category exists
- Check that you clicked the + button on the correct category

### Can't Delete Category
- Error: "Cannot delete category with subcategories"
- Solution: Delete all child categories first

## Re-seeding Categories

To update Firestore with the latest seeded data (including emoji icons):

```bash
cd backend
GOOGLE_APPLICATION_CREDENTIALS="$PWD/src/main/resources/firebase-service-account.json" \
  ./gradlew bootRun --args='--spring.profiles.active=seed'
```

Wait for: `✅ Firestore data seeding completed successfully!`
Then press Ctrl+C to stop.

Refresh the admin dashboard to see updated icons.
