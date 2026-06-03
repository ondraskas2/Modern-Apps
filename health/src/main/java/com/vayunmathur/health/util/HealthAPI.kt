package com.vayunmathur.health.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.util.Tuple3
import java.time.ZoneOffset
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

object HealthAPI {
    lateinit var healthConnectClient: HealthConnectClient
    lateinit var db: HealthDatabase
    lateinit var preferences: SharedPreferences

    fun init(healthConnectClient: HealthConnectClient, context: Context, db: HealthDatabase) {
        this.healthConnectClient = healthConnectClient
        this.db = db
        preferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    }

    @Composable
    fun sumInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumProteinInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumProteinInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumCarbsInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumCarbsInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumFatInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumFatInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumFiberInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumFiberInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumSugarInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumSugarInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumSodiumInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumSodiumInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumBiotinInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumBiotinInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumCaffeineInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumCaffeineInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumCalciumInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumCalciumInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumChlorideInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumChlorideInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumCholesterolInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumCholesterolInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumChromiumInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumChromiumInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumCopperInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumCopperInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumFolateInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumFolateInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumFolicAcidInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumFolicAcidInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumIodineInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumIodineInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumIronInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumIronInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumMagnesiumInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumMagnesiumInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumManganeseInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumManganeseInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumMolybdenumInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumMolybdenumInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumMonounsaturatedFatInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumMonounsaturatedFatInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumNiacinInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumNiacinInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumPantothenicAcidInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumPantothenicAcidInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumPhosphorusInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumPhosphorusInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumPolyunsaturatedFatInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumPolyunsaturatedFatInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumPotassiumInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumPotassiumInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumRiboflavinInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumRiboflavinInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumSaturatedFatInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumSaturatedFatInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumSeleniumInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumSeleniumInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumThiaminInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumThiaminInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumTransFatInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumTransFatInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumUnsaturatedFatInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumUnsaturatedFatInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumVitaminAInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumVitaminAInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumVitaminB12InRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumVitaminB12InRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumVitaminB6InRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumVitaminB6InRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumVitaminCInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumVitaminCInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumVitaminDInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumVitaminDInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumVitaminEInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumVitaminEInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumVitaminKInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumVitaminKInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun sumZincInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().sumZincInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun maxInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double?> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().maxInRange(recordType, startTime, endTime)
        }
    }

    @Composable
    fun minInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double?> {
        return remember(recordType, startTime, endTime) {
            db.healthDao().minInRange(recordType, startTime, endTime)
        }
    }

    suspend inline fun lastRecord(recordType: RecordType): Record? {
        return db.healthDao().getLastRecord(recordType)
    }

    fun getAllRecordsInRange(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant
    ): Flow<List<Record>> {
        return db.healthDao().getAllInRange(recordType, startTime, endTime)
    }

    suspend fun deleteRecord(record: Record) {
        // Delete from local Room DB
        db.healthDao().deleteByIds(listOf(record.primaryKey))

        // Delete from Health Connect if it has a valid ID
        try {
            val recordClass: KClass<out androidx.health.connect.client.records.Record>? = when (record.type) {
                RecordType.Nutrition -> NutritionRecord::class
                RecordType.Hydration -> HydrationRecord::class
                else -> null
            }
            if (recordClass != null) {
                healthConnectClient.deleteRecords(
                        recordType = recordClass,
                        recordIdsList = listOf(record.id),
                        clientRecordIdsList = listOf(record.id)
                )
            }
        } catch (e: Exception) {
            Log.e("HealthAPI", "Failed to delete record from Health Connect", e)
        }
    }

    suspend fun writeHealthRecord(record: Record) {
        Log.d("HealthAPI", "writeHealthRecord: type=${record.type}, metadata=${record.metadata}")
        val startInstant = record.startTime
        val endInstant = record.endTime

        val hcRecord: androidx.health.connect.client.records.Record =
                when (record.type) {
                    RecordType.Nutrition -> {
                        val nd = record.nutritionData ?: return
                        NutritionRecord(
                                startTime = startInstant,
                                startZoneOffset =
                                        ZoneOffset.systemDefault().rules.getOffset(startInstant),
                                endTime = endInstant,
                                endZoneOffset =
                                        ZoneOffset.systemDefault().rules.getOffset(endInstant),
                                name = record.metadata,
                                energy = Energy.kilocalories(nd.calories),
                                protein = Mass.grams(nd.protein),
                                totalCarbohydrate = Mass.grams(nd.carbohydrates),
                                totalFat = Mass.grams(nd.fat),
                                dietaryFiber = Mass.grams(nd.fiber),
                                sugar = Mass.grams(nd.sugar),
                                sodium = Mass.milligrams(nd.sodium),
                                biotin = Mass.micrograms(nd.biotin),
                                caffeine = Mass.milligrams(nd.caffeine),
                                calcium = Mass.milligrams(nd.calcium),
                                chloride = Mass.milligrams(nd.chloride),
                                cholesterol = Mass.milligrams(nd.cholesterol),
                                chromium = Mass.micrograms(nd.chromium),
                                copper = Mass.milligrams(nd.copper),
                                folate = Mass.micrograms(nd.folate),
                                folicAcid = Mass.micrograms(nd.folicAcid),
                                iodine = Mass.micrograms(nd.iodine),
                                iron = Mass.milligrams(nd.iron),
                                magnesium = Mass.milligrams(nd.magnesium),
                                manganese = Mass.milligrams(nd.manganese),
                                molybdenum = Mass.micrograms(nd.molybdenum),
                                monounsaturatedFat = Mass.grams(nd.monounsaturatedFat),
                                niacin = Mass.milligrams(nd.niacin),
                                pantothenicAcid = Mass.milligrams(nd.pantothenicAcid),
                                phosphorus = Mass.milligrams(nd.phosphorus),
                                polyunsaturatedFat = Mass.grams(nd.polyunsaturatedFat),
                                potassium = Mass.milligrams(nd.potassium),
                                riboflavin = Mass.milligrams(nd.riboflavin),
                                saturatedFat = Mass.grams(nd.saturatedFat),
                                selenium = Mass.micrograms(nd.selenium),
                                thiamin = Mass.milligrams(nd.thiamin),
                                transFat = Mass.grams(nd.transFat),
                                unsaturatedFat = Mass.grams(nd.unsaturatedFat),
                                vitaminA = Mass.micrograms(nd.vitaminA),
                                vitaminB12 = Mass.micrograms(nd.vitaminB12),
                                vitaminB6 = Mass.milligrams(nd.vitaminB6),
                                vitaminC = Mass.milligrams(nd.vitaminC),
                                vitaminD = Mass.micrograms(nd.vitaminD),
                                vitaminE = Mass.milligrams(nd.vitaminE),
                                vitaminK = Mass.micrograms(nd.vitaminK),
                                zinc = Mass.milligrams(nd.zinc),
                                metadata = Metadata.manualEntry(clientRecordId = record.id)
                        )
                    }
                    RecordType.Hydration -> {
                        HydrationRecord(
                                startTime = startInstant,
                                startZoneOffset =
                                        ZoneOffset.systemDefault().rules.getOffset(startInstant),
                                endTime = endInstant,
                                endZoneOffset =
                                        ZoneOffset.systemDefault().rules.getOffset(endInstant),
                                volume = Volume.liters(record.value),
                                metadata = Metadata.manualEntry(clientRecordId = record.id)
                        )
                    }
                    else -> return
                }

        try {
            val response = healthConnectClient.insertRecords(listOf(hcRecord))
            val newId = response.recordIdsList.firstOrNull()
            if (newId != null) {
                Log.i("HealthAPI", "Successfully wrote record to Health Connect with ID: $newId")
                // Remove old local record and replace with one containing HC ID
                db.healthDao().deleteByIds(listOf(record.primaryKey))
                val updatedRecord = record.copy(id = newId, primaryKey = "$newId-${record.index}")
                db.healthDao().upsert(listOf(updatedRecord))
            }
        } catch (e: Exception) {
            Log.e("HealthAPI", "Failed to write record to Health Connect", e)
        }
    }

    enum class PeriodType {
        Hourly,
        Daily,
        Weekly,
        Monthly
    }

    private val hourlyFormat =
            LocalDateTime.Format {
                year()
                chars("-")
                monthNumber()
                chars("-")
                day()
                chars(" ")
                hour()
                chars(":")
                minute()
            }

    suspend fun getListOfAverages(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant,
            period: PeriodType
    ): List<Tuple3<Long, Double, Double>> {
        when (period) {
            PeriodType.Daily -> {
                // yyyy-mm-dd
                val dailySums =
                        db.healthDao().getDailyAvgs(recordType, startTime, endTime).sortedBy {
                            it.day
                        }
                return dailySums.map {
                    Tuple3(LocalDate.parse(it.day).toEpochDays(), it.totalValue, it.totalValue2)
                }
            }
            PeriodType.Weekly -> {
                val dailySums =
                        db.healthDao()
                                .getDailyAvgs(recordType, startTime, endTime)
                                .sortedBy { it.day }
                                .groupBy {
                                    val date = LocalDate.parse(it.day)
                                    val firstDayOfWeek =
                                            date.plus(
                                                    (date.dayOfWeek.ordinal + 1) % 7,
                                                    DateTimeUnit.DAY
                                            )
                                    firstDayOfWeek.toEpochDays()
                                }
                                .mapValues { day ->
                                    day.value.map { it.totalValue }.average() to
                                            day.value.map { it.totalValue2 }.average()
                                }
                                .map { Tuple3(it.key, it.value.first, it.value.second) }
                return dailySums
            }
            PeriodType.Monthly -> {
                val dailySums =
                        db.healthDao()
                                .getDailyAvgs(recordType, startTime, endTime)
                                .sortedBy { it.day }
                                .groupBy {
                                    val date = LocalDate.parse(it.day)
                                    val firstDayOfMonth = date.minus(date.day - 1, DateTimeUnit.DAY)
                                    firstDayOfMonth.toEpochDays()
                                }
                                .mapValues { day ->
                                    day.value.map { it.totalValue }.average() to
                                            day.value.map { it.totalValue2 }.average()
                                }
                                .map { Tuple3(it.key, it.value.first, it.value.second) }
                return dailySums
            }
            else -> {
                val hourlySums =
                        db.healthDao()
                                .getHourlyAvgs(
                                        recordType,
                                        startTime.toEpochMilliseconds(),
                                        endTime.toEpochMilliseconds()
                                )
                                .sortedBy { it.hourBlock }
                return hourlySums.map {
                    val date = hourlyFormat.parse(it.hourBlock)
                    Tuple3(date.date.toEpochDays() * 24 + date.hour, it.totalValue, it.totalValue2)
                }
            }
        }
    }

    suspend fun getListOfSums(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant,
            period: PeriodType
    ): List<Tuple3<Long, Double, Double>> {
        when (period) {
            PeriodType.Daily -> {
                // yyyy-mm-dd
                val dailySums =
                        db.healthDao().getDailySums(recordType, startTime, endTime).sortedBy {
                            it.day
                        }
                return dailySums.map {
                    Tuple3(LocalDate.parse(it.day).toEpochDays(), it.totalValue, it.totalValue2)
                }
            }
            PeriodType.Weekly -> {
                val dailySums =
                        db.healthDao()
                                .getDailySums(recordType, startTime, endTime)
                                .sortedBy { it.day }
                                .groupBy {
                                    val date = LocalDate.parse(it.day)
                                    val firstDayOfWeek =
                                            date.plus(
                                                    (date.dayOfWeek.ordinal + 1) % 7,
                                                    DateTimeUnit.DAY
                                            )
                                    firstDayOfWeek.toEpochDays()
                                }
                                .mapValues { day ->
                                    day.value.map { it.totalValue }.average() to
                                            day.value.map { it.totalValue2 }.average()
                                }
                                .map { Tuple3(it.key, it.value.first, it.value.second) }
                return dailySums
            }
            PeriodType.Monthly -> {
                val dailySums =
                        db.healthDao()
                                .getDailySums(recordType, startTime, endTime)
                                .sortedBy { it.day }
                                .groupBy {
                                    val date = LocalDate.parse(it.day)
                                    val firstDayOfMonth = date.minus(date.day - 1, DateTimeUnit.DAY)
                                    firstDayOfMonth.toEpochDays()
                                }
                                .mapValues { day ->
                                    day.value.map { it.totalValue }.average() to
                                            day.value.map { it.totalValue2 }.average()
                                }
                                .map { Tuple3(it.key, it.value.first, it.value.second) }
                return dailySums
            }
            else -> {
                val hourlySums =
                        db.healthDao()
                                .getHourlySums(
                                        recordType,
                                        startTime.toEpochMilliseconds(),
                                        endTime.toEpochMilliseconds()
                                )
                                .sortedBy { it.hourBlock }
                return hourlySums.map {
                    val date = hourlyFormat.parse(it.hourBlock)
                    Tuple3(date.date.toEpochDays() * 24 + date.hour, it.totalValue, it.totalValue2)
                }
            }
        }
    }
}
