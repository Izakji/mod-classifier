# Manual Mod Curation System

The Mod Classifier uses a hybrid approach with **manual curation as the highest priority**. This ensures maximum accuracy for known mods while falling back to algorithmic analysis for unknown ones.

## 📁 Curation File Location

The system looks for curated mod data in this order:

1. **External file**: `config/mod-classifier/curated-mods.json` (recommended)
2. **Built-in resource**: `/curated-mods.json` (fallback)
3. **Hardcoded fallback**: Minimal entries for JEI and Sodium

## 📋 JSON Format

```json
[
  {
    "mod_name": "Just Enough Items",
    "page_url": "https://modrinth.com/mod/jei",
    "source": "modrinth",
    "classification": "CLIENT_ONLY",
    "confidence": 0.95,
    "reason": [
      {"label": "UI mod", "snippet": "Recipe viewer with client-side interface"},
      {"label": "optional on server", "snippet": "Server installation is optional"}
    ],
    "scanned_at": "2025-08-17T15:42:10.123456"
  }
]
```

### Field Descriptions

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `mod_name` | string | ✅ | Human-readable mod name |
| `page_url` | string | ✅ | URL to mod page (used to extract mod ID) |
| `source` | string | ✅ | Data source (`modrinth`, `curseforge`, `manual`, etc.) |
| `classification` | string | ✅ | One of: `CLIENT_ONLY`, `SERVER_ONLY`, `UNIVERSAL`, `UNKNOWN` |
| `confidence` | number | ✅ | Confidence level (0.0 - 1.0) |
| `reason` | array | ✅ | Array of reason objects with `label` and `snippet` |
| `scanned_at` | string | ✅ | ISO timestamp of when data was collected |

## 🔗 Mod ID Extraction

The system extracts mod IDs from URLs:
- `https://modrinth.com/mod/jei` → `jei`
- `https://curseforge.com/projects/applied-energistics-2` → `applied-energistics-2`

If URL extraction fails, it converts the mod name:
- `"Just Enough Items"` → `just_enough_items`

## 🎯 Classification Types

### CLIENT_ONLY
Mods that only work/are needed on the client:
- UI/HUD improvements (JEI, WAILA, etc.)
- Rendering optimizations (Sodium, OptiFine)
- Audio enhancements
- Client-side utilities

### SERVER_ONLY  
Mods that only work/are needed on the server:
- Server administration tools
- Performance monitoring
- World management

### UNIVERSAL
Mods required on both client and server:
- Content mods (tech, magic, dimensions)
- Gameplay mechanics
- World generation
- Most content-adding mods

### UNKNOWN
Classification uncertain or failed

## 📊 Confidence Levels

| Range | Meaning | Usage |
|-------|---------|-------|
| 0.95 - 1.00 | Very High | Definitive evidence, official documentation |
| 0.85 - 0.94 | High | Strong evidence, community consensus |
| 0.70 - 0.84 | Good | Solid evidence, testing confirmed |
| 0.50 - 0.69 | Moderate | Some evidence, likely correct |
| 0.30 - 0.49 | Low | Weak evidence, uncertain |
| 0.00 - 0.29 | Very Low | Guess, placeholder |

## 🔄 Loading and Reloading

The system loads curation data at startup and logs:
```
[INFO] Loading curated mods from: config/mod-classifier/curated-mods.json
[INFO] Successfully loaded 156 curated mod classifications
```

To reload without restarting:
```java
ManualCuration.getInstance().reloadCurations();
```

## 📈 Statistics and Monitoring

The system provides detailed statistics:

```
=== Manual Curation Summary ===
Total curated mods: 156
By classification:
  CLIENT_ONLY: 89 mods
  UNIVERSAL: 63 mods
  SERVER_ONLY: 4 mods
By source:
  modrinth: 134 mods
  curseforge: 18 mods
  manual: 4 mods
Average confidence: 0.87
```

## 🔧 Runtime Management

### Adding Overrides
```java
ManualCuration.getInstance().addManualOverride("new_mod", ModType.CLIENT_ONLY, "Testing");
```

### Checking Classifications
```java
ModType type = ManualCuration.getInstance().getManualClassification("jei");
CuratedModEntry entry = ManualCuration.getInstance().getCuratedEntry("jei");
```

## 🎮 Integration with Hybrid System

Manual curation is **Priority 1** in the hybrid classification:

1. **Manual Curation** (confidence from JSON file) - Highest priority
2. **Evidence-Based Analysis** - Fallback for unknown mods
3. **Default to UNIVERSAL** - Safe fallback when uncertain

## 📝 Best Practices

### For Curators
- **Start with popular mods** - Higher impact
- **Use official documentation** when available  
- **Test classifications** in real environments
- **Document reasoning clearly**
- **Use appropriate confidence levels**

### For Users
- **Place JSON file** in `config/mod-classifier/`
- **Keep backups** of your curation data
- **Monitor logs** for loading issues
- **Update classifications** as mods change

## 🤝 Community Collaboration

The JSON format makes it easy to:
- **Share curation databases** between users
- **Merge multiple sources** of data  
- **Track changes** with version control
- **Automate collection** from mod hosting sites

This creates a self-improving, community-maintained database of mod classifications!