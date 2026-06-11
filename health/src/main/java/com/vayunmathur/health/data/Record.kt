package com.vayunmathur.health.data
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Delete
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import java.time.Instant

enum class RecordType {
    Steps, Wheelchair, Distance, CaloriesTotal, CaloriesActive, CaloriesBasal, Floors, Elevation,
    HeartRate, RestingHeartRate, HeartRateVariabilityRmssd, RespiratoryRate, OxygenSaturation,
    BloodPressure, BloodGlucose, Vo2Max, SkinTemperature,
    Weight, Height, BodyFat, LeanBodyMass, BoneMass, BodyWaterMass,
    Sleep, Mindfulness, Hydration, Nutrition, Exercise
}

@Serializable
data class NutritionData(
    val protein: Double = 0.0,
    val carbohydrates: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sugar: Double = 0.0,
    val sodium: Double = 0.0,
    val biotin: Double = 0.0,
    val caffeine: Double = 0.0,
    val calcium: Double = 0.0,
    val chloride: Double = 0.0,
    val cholesterol: Double = 0.0,
    val chromium: Double = 0.0,
    val copper: Double = 0.0,
    val folate: Double = 0.0,
    val folicAcid: Double = 0.0,
    val iodine: Double = 0.0,
    val iron: Double = 0.0,
    val magnesium: Double = 0.0,
    val manganese: Double = 0.0,
    val molybdenum: Double = 0.0,
    val monounsaturatedFat: Double = 0.0,
    val niacin: Double = 0.0,
    val pantothenicAcid: Double = 0.0,
    val phosphorus: Double = 0.0,
    val polyunsaturatedFat: Double = 0.0,
    val potassium: Double = 0.0,
    val riboflavin: Double = 0.0,
    val saturatedFat: Double = 0.0,
    val selenium: Double = 0.0,
    val thiamin: Double = 0.0,
    val transFat: Double = 0.0,
    val unsaturatedFat: Double = 0.0,
    val vitaminA: Double = 0.0,
    val vitaminB12: Double = 0.0,
    val vitaminB6: Double = 0.0,
    val vitaminC: Double = 0.0,
    val vitaminD: Double = 0.0,
    val vitaminE: Double = 0.0,
    val vitaminK: Double = 0.0,
    val zinc: Double = 0.0,
    val calories: Double = 0.0
)

@Serializable
data class SleepStage(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val stage: Int
)

@Serializable
data class SleepData(
    val awakeDurationMillis: Long = 0,
    val remDurationMillis: Long = 0,
    val lightDurationMillis: Long = 0,
    val deepDurationMillis: Long = 0,
    val unknownDurationMillis: Long = 0,
    val stagesJson: String? = null
)

@Serializable
data class ExerciseSegmentData(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val segmentType: Int,
    val repetitions: Int,
)

@Serializable
data class ExerciseLapData(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val lengthMeters: Double?,
)

@Serializable
data class ExerciseData(
    val exerciseType: Int = 0,
    val title: String? = null,
    val notes: String? = null,
    val segmentsJson: String? = null,
    val lapsJson: String? = null,
    val hasRoute: Boolean = false,
)

@Entity(indices = [Index(value = ["type", "startTime", "endTime"])])
data class Record(
    val id: String,
    val index : Int, // for multisample records
    val type: RecordType,
    val startTime: Instant,
    val endTime: Instant,
    val value: Double,
    val secondaryValue: Double = 0.0,
    @Embedded(prefix = "nutrition_") val nutritionData: NutritionData? = null,
    @Embedded(prefix = "sleep_") val sleepData: SleepData? = null,
    @Embedded(prefix = "exercise_") val exerciseData: ExerciseData? = null,
    val metadata: String? = null,
    @PrimaryKey val primaryKey: String = "$id-$index",
)

@Dao
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<Record>)

    @Query("DELETE FROM Record WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC")
    suspend fun getRecords(type: RecordType): List<Record>

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC")
    fun getRecordsFlow(type: RecordType): Flow<List<Record>>

    @Query("SELECT * FROM Record WHERE primaryKey = :id")
    suspend fun getRecord(id: String): Record?

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastRecord(type: RecordType): Record?

    @Query("SELECT COALESCE(SUM(CASE WHEN :type = 'Nutrition' THEN nutrition_calories ELSE value END), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>
    
    @Query("SELECT COALESCE(SUM(nutrition_protein), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumProteinInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_carbohydrates), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumCarbsInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_fat), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumFatInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_fiber), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumFiberInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_sugar), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumSugarInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_sodium), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumSodiumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_biotin), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumBiotinInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_caffeine), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumCaffeineInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_calcium), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumCalciumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_chloride), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumChlorideInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_cholesterol), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumCholesterolInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_chromium), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumChromiumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_copper), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumCopperInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_folate), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumFolateInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_folicAcid), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumFolicAcidInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_iodine), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumIodineInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_iron), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumIronInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_magnesium), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumMagnesiumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_manganese), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumManganeseInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_molybdenum), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumMolybdenumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_monounsaturatedFat), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumMonounsaturatedFatInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_niacin), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumNiacinInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_pantothenicAcid), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumPantothenicAcidInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_phosphorus), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumPhosphorusInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_polyunsaturatedFat), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumPolyunsaturatedFatInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_potassium), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumPotassiumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_riboflavin), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumRiboflavinInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_saturatedFat), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumSaturatedFatInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_selenium), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumSeleniumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_thiamin), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumThiaminInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_transFat), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumTransFatInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_unsaturatedFat), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumUnsaturatedFatInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_vitaminA), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumVitaminAInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_vitaminB12), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumVitaminB12InRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_vitaminB6), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumVitaminB6InRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_vitaminC), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumVitaminCInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_vitaminD), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumVitaminDInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_vitaminE), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumVitaminEInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_vitaminK), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumVitaminKInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(nutrition_zinc), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumZincInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    @Query("SELECT COALESCE(SUM(value), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun sumInRangeGet1(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double
    @Query("SELECT COALESCE(SUM(secondaryValue), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun sumInRangeGet2(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double
    @Query("SELECT AVG(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun avgInRangeGet1(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double?
    @Query("SELECT AVG(secondaryValue) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun avgInRangeGet2(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double?
    @Query("SELECT MIN(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun minInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?>
    @Query("SELECT MAX(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun maxInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?>
    @Query("SELECT * FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun getAllInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<List<Record>>

    @Query("""
    SELECT 
        date(startTime / 1000, 'unixepoch', 'localtime') as day, 
        SUM(value) as totalValue,
        SUM(secondaryValue) as totalValue2 
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailySums(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant
    ): List<DailySum>

    @Query("""
    SELECT 
        strftime('%Y-%m-%d %H:00', startTime / 1000, 'unixepoch', 'localtime') AS hourBlock, 
        SUM(value) AS totalValue,
        SUM(secondaryValue) AS totalValue2
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY hourBlock
    ORDER BY hourBlock ASC
""")
    suspend fun getHourlySums(
        type: RecordType,
        startTime: Long,
        endTime: Long
    ): List<HourlySum>

    @Query("""
    SELECT 
        date(startTime / 1000, 'unixepoch', 'localtime') as day, 
        AVG(value) as totalValue,
        AVG(secondaryValue) as totalValue2 
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailyAvgs(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant
    ): List<DailySum>

    @Query("""
    SELECT 
        strftime('%Y-%m-%d %H:00', startTime / 1000, 'unixepoch', 'localtime') AS hourBlock, 
        AVG(value) AS totalValue,
        AVG(secondaryValue) AS totalValue2
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY hourBlock
    ORDER BY hourBlock ASC
""")
    suspend fun getHourlyAvgs(
        type: RecordType,
        startTime: Long,
        endTime: Long
    ): List<HourlySum>

    // Helper data class to catch the results
    data class DailySum(
        val day: String, // Format: YYYY-MM-DD
        val totalValue: Double,
        val totalValue2: Double
    )
    data class HourlySum(
        val hourBlock: String, // Format: 2026-03-03 15:00
        val totalValue: Double,
        val totalValue2: Double
    )

    // Food Logger Methods
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngredient(ingredient: Ingredient)

    @Update
    suspend fun updateIngredient(ingredient: Ingredient)

    @Delete
    suspend fun deleteIngredient(ingredient: Ingredient)

    @Query("SELECT * FROM Ingredient ORDER BY originalName ASC")
    suspend fun getAllIngredients(): List<Ingredient>

    @Query("SELECT * FROM Ingredient ORDER BY originalName ASC")
    fun getAllIngredientsFlow(): Flow<List<Ingredient>>

    @Query("SELECT * FROM Ingredient WHERE isRecipe = 1 ORDER BY originalName ASC")
    fun getIngredientsAsRecipesFlow(): Flow<List<Ingredient>>

    @Query("SELECT * FROM Ingredient WHERE id = :id")
    suspend fun getIngredient(id: String): Ingredient?

    @Query("SELECT * FROM Ingredient WHERE originalName LIKE '%' || :query || '%' OR customName LIKE '%' || :query || '%'")
    suspend fun searchIngredients(query: String): List<Ingredient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: Recipe)

    @Update
    suspend fun updateRecipe(recipe: Recipe)

    @Delete
    suspend fun deleteRecipe(recipe: Recipe)

    @Query("SELECT * FROM Recipe ORDER BY name ASC")
    fun getAllRecipesFlow(): Flow<List<Recipe>>

    @Query("SELECT * FROM Recipe WHERE id = :id")
    suspend fun getRecipe(id: String): Recipe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServingUnit(unit: ServingUnit)

    @Delete
    suspend fun deleteServingUnit(unit: ServingUnit)

    @Query("SELECT * FROM ServingUnit WHERE ingredientId = :ingredientId")
    suspend fun getUnitsForIngredient(ingredientId: String): List<ServingUnit>

    @Query("SELECT * FROM ServingUnit WHERE recipeId = :recipeId")
    suspend fun getUnitsForRecipe(recipeId: String): List<ServingUnit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipeIngredient(recipeIngredient: RecipeIngredient)

    @Delete
    suspend fun deleteRecipeIngredient(recipeIngredient: RecipeIngredient)

    @Query("SELECT * FROM RecipeIngredient WHERE recipeId = :recipeId")
    suspend fun getIngredientsForRecipe(recipeId: String): List<RecipeIngredient>
    
    @Query("SELECT * FROM RecipeIngredient WHERE recipeId = :recipeId")
    fun getIngredientsForRecipeFlow(recipeId: String): Flow<List<RecipeIngredient>>
}

@Database(
    entities = [Record::class, Ingredient::class, Recipe::class, ServingUnit::class, RecipeIngredient::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations = listOf(
            object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE Record ADD COLUMN exercise_exerciseType INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE Record ADD COLUMN exercise_title TEXT")
                    db.execSQL("ALTER TABLE Record ADD COLUMN exercise_notes TEXT")
                    db.execSQL("ALTER TABLE Record ADD COLUMN exercise_segmentsJson TEXT")
                    db.execSQL("ALTER TABLE Record ADD COLUMN exercise_lapsJson TEXT")
                    db.execSQL("ALTER TABLE Record ADD COLUMN exercise_hasRoute INTEGER NOT NULL DEFAULT 0")
                }
            }
        )
    }
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun dateToTimestamp(date: Instant?): Long? = date?.toEpochMilli()

    @TypeConverter
    fun toTS(date: kotlin.time.Instant): Long = date.toEpochMilliseconds()

    @TypeConverter
    fun fromTS(timestamp: Long): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(timestamp)
}