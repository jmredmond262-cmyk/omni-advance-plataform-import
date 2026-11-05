from pathlib import Path

activity = Path("admin/src/main/java/com/omni/admin/MainActivity.kt")
source = activity.read_text(encoding="utf-8")

replacements = [
    ('contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),\n      verticalArrangement = Arrangement.spacedBy(14.dp)',
     'contentPadding = PaddingValues(top = 12.dp, bottom = 22.dp),\n      verticalArrangement = Arrangement.spacedBy(11.dp)'),
    ('Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))\n            .background(Brush.linearGradient(listOf(Color(0xFF123A5D), Color(0xFF101B35), Color(0xFF0A1426))))\n            .border(1.dp, Color(0xFF1679A6), RoundedCornerShape(22.dp)).padding(18.dp),',
     'Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))\n            .background(Brush.linearGradient(listOf(Color(0xFF10324C), Color(0xFF0D1A2D), Color(0xFF0A1426))))\n            .border(1.dp, Color(0xFF17617E), RoundedCornerShape(18.dp)).padding(14.dp),'),
    ('Modifier.size(58.dp).clip(RoundedCornerShape(50))', 'Modifier.size(48.dp).clip(RoundedCornerShape(50))'),
    ('Text("Centro de mando de OMNI", color = White, fontSize = if (tabletLayout) 23.sp else 19.sp, fontWeight = FontWeight.ExtraBold)\n            Text("Panel ejecutivo del ecosistema OMNI", color = Muted, fontSize = 12.sp)',
     'Text("Centro de mando", color = White, fontSize = if (tabletLayout) 21.sp else 18.sp, fontWeight = FontWeight.ExtraBold)\n            Text("Visión ejecutiva del ecosistema OMNI", color = Muted, fontSize = 11.sp)'),
    ('      items(metrics.chunked(columns)) { rowMetrics ->',
     '      item {\n        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {\n          Text("INDICADORES DE GESTIÓN", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.35.sp)\n          Text("Volumen, actividad y operación de la plataforma", color = Muted, fontSize = 11.sp)\n        }\n      }\n      items(metrics.chunked(columns)) { rowMetrics ->'),
    ('val accents = listOf(Color(0xFF16CFFF), Color(0xFF32E7A0), Color(0xFFBD70FF), Color(0xFFFFB52E))\n            val accent = accents[(metrics.indexOf(metric) % accents.size)]',
     'val accent = Mint'),
    ('Modifier.weight(1f).heightIn(min = 88.dp)', 'Modifier.weight(1f).heightIn(min = 76.dp)'),
    ('.padding(9.dp),\n                verticalArrangement = Arrangement.spacedBy(4.dp)',
     '.padding(horizontal = 9.dp, vertical = 8.dp),\n                verticalArrangement = Arrangement.spacedBy(3.dp)'),
    ('Icon(metric.third, null, tint = accent, modifier = Modifier.size(18.dp))',
     'Icon(metric.third, null, tint = accent, modifier = Modifier.size(16.dp))'),
    ('Icon(Icons.Default.ArrowOutward, contentDescription = "Abrir ${metric.first}", tint = accent, modifier = Modifier.size(13.dp))',
     'Icon(Icons.Default.ArrowOutward, contentDescription = "Abrir ${metric.first}", tint = accent.copy(alpha = 0.8f), modifier = Modifier.size(11.dp))'),
    ('Text(metric.second, color = White, fontSize = if (tabletLayout) 21.sp else 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)\n                Text(metric.first, color = Muted, fontSize = 9.sp, maxLines = 2, lineHeight = 11.sp)',
     'Text(metric.second, color = White, fontSize = if (tabletLayout) 19.sp else 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)\n                Text(metric.first, color = Muted, fontSize = 9.sp, maxLines = 2, lineHeight = 10.sp)'),
    ('Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp))',
     'Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp))'),
    ('Text("Evolución de OMNI", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)',
     'Text("Evolución de OMNI", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)'),
    ('Row(Modifier.fillMaxWidth().height(142.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom)',
     'Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom)'),
    ('Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp))',
     'Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp))'),
    ('Row(Modifier.fillMaxWidth().height(82.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom)',
     'Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom)'),
]

for old, new in replacements:
    if new in source:
        continue
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Patch abortado: se esperaba una coincidencia y se encontraron {count}: {old[:90]!r}")
    source = source.replace(old, new, 1)

activity.write_text(source, encoding="utf-8")

gradle = Path("admin/build.gradle.kts")
build = gradle.read_text(encoding="utf-8")
if 'versionName = "1.4.1"' in build:
    build = build.replace('versionName = "1.4.1"', 'versionName = "1.4.2"', 1)
elif 'versionName = "1.4.2"' not in build:
    raise SystemExit("No se pudo validar versionName de OMNI Admin.")
gradle.write_text(build, encoding="utf-8")

print("Dashboard OMNI Admin 1.4.2 aplicado.")
