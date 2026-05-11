import sys

path = '/home/vayun/Documents/Modern-Apps/maps/src/main/java/com/vayunmathur/maps/util/OfflineRouter.kt'
with open(path, 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False
for line in lines:
    if 'private val _trafficSegments' in line:
        new_lines.append('    private val _trafficGeoJsonUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)\n')
        new_lines.append('    val trafficGeoJsonUrl = _trafficGeoJsonUrl.asStateFlow()\n')
        new_lines.append('\n')
        new_lines.append('    private var appContext: Context? = null\n')
        new_lines.append('\n')
        new_lines.append('    fun initialize(context: Context) {\n')
        new_lines.append('        appContext = context.applicationContext\n')
        new_lines.append('        if (!isInitialized) {\n')
        new_lines.append('            isInitialized = init(context.getExternalFilesDir(null)!!.absolutePath)\n')
        new_lines.append('        }\n')
        new_lines.append('    }\n')
        new_lines.append('\n')
        new_lines.append('    fun updateTrafficGeoJson(context: Context) {\n')
        new_lines.append('        val raw = getTrafficSegmentsNative()\n')
        new_lines.append('        if (raw.isEmpty()) {\n')
        new_lines.append('            _trafficGeoJsonUrl.value = null\n')
        new_lines.append('            return\n')
        new_lines.append('        }\n')
        new_lines.append('\n')
        new_lines.append('        try {\n')
        new_lines.append('            val file = java.io.File(context.cacheDir, "traffic.geojson")\n')
        new_lines.append('            file.bufferedWriter().use { writer ->\n')
        new_lines.append('                writer.write("{\\"type\\":\\"FeatureCollection\\",\\"features\\":[")\n')
        new_lines.append('                for (i in raw.indices step 5) {\n')
        new_lines.append('                    if (i + 4 >= raw.size) break\n')
        new_lines.append('                    if (i > 0) writer.write(",")\n')
        new_lines.append('                    val lat1 = raw[i]\n')
        new_lines.append('                    val lon1 = raw[i + 1]\n')
        new_lines.append('                    val lat2 = raw[i + 2]\n')
        new_lines.append('                    val lon2 = raw[i + 3]\n')
        new_lines.append('                    val ratio = raw[i + 4]\n')
        new_lines.append('\n')
        new_lines.append('                    val color = when {\n')
        new_lines.append('                        ratio < 0.5 -> \\"#FF0000\\" // Red\n')
        new_lines.append('                        ratio < 0.9 -> \\"#FFFF00\\" // Yellow\n')
        new_lines.append('                        else -> \\"#00FF00\\" // Green\n')
        new_lines.append('                    }\n')
        new_lines.append('\n')
        new_lines.append('                    writer.write(\\"{\\\\\\"type\\\\\\":\\\\\\"Feature\\\\\\",\\\\\\"geometry\\\\\\":{\\\\\\"type\\\\\\":\\\\\\"LineString\\\\\\",\\\\\\"coordinates\\\\\\":[[$lon1,$lat1],[$lon2,$lat2]]},\\\\\\"properties\\\\\\":{\\\\\\"color\\\\\\":\\\\\\"$color\\\\\\"}}\\")\n')
        new_lines.append('                }\n')
        new_lines.append('                writer.write(\"]}\")\n')
        new_lines.append('            }\n')
        new_lines.append('            _trafficGeoJsonUrl.value = \"file://${file.absolutePath}\"\n')
        new_lines.append('        } catch (e: Exception) {\n')
        new_lines.append('            Log.e(\"OFFLINE_ROUTER\", \"Failed to write traffic GeoJSON\", e)\n')
        new_lines.append('        }\n')
        new_lines.append('    }\n')
        skip = True
        continue
    if 'val trafficSegments = _trafficSegments.asStateFlow()' in line:
        continue
    if 'fun updateTrafficView()' in line:
        skip = True
        continue
    if skip and '    private external fun ensureTrafficLoadedNative' in line:
        skip = False
    if skip:
        continue
    if 'updateTrafficView()' in line:
        new_lines.append('                    appContext?.let { updateTrafficGeoJson(it) }\n')
        continue
    new_lines.append(line)

with open(path, 'w') as f:
    f.writelines(new_lines)
