package org.lightink.reader.utils

import com.google.common.base.Throwables
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.vertx.core.Handler

import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.mysqlclient.preparedQueryAwait
import io.vertx.mysqlclient.MySQLPool
import io.vertx.sqlclient.Tuple
import mu.KotlinLogging
import org.lightink.reader.entity.BasicError
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.File
import java.nio.file.Paths
import org.lightink.reader.config.AppConfig
import com.google.gson.reflect.TypeToken
import kotlin.reflect.KProperty1
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties
import io.legado.app.data.entities.Book

/**
 * @Auther: zoharSoul
 * @Date: 2019-05-21 16:17
 * @Description:
 */
private val logger = KotlinLogging.logger {}

val gson = GsonBuilder().disableHtmlEscaping().create()

var storageFinalPath = ""
var workDirPath = ""

fun RoutingContext.success(any: Any?) {
    val toJson: String = if (any is JsonObject) {
        any.toString()
    } else {
        gson.toJson(any)
    }
    this.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .end(toJson)
}

fun RoutingContext.error(throwable: Throwable) {
    val path = URLDecoder.decode(this.request().absoluteURI())
    val basicError = BasicError(
            "Internal Server Error",
            throwable.toString(),
            throwable.message.toString(),
            path,
            500,
            System.currentTimeMillis()
    )

    val errorJson = gson.toJson(basicError)
    logger.error("Internal Server Error", throwable)
    logger.error { errorJson }

    this.response()
            .putHeader("content-type", "application/json; charset=utf-8")
            .setStatusCode(500)
            .end(errorJson)
}

fun getWorkDir(subPath: String): String {
    if (workDirPath.isEmpty()) {
        var osName = System.getProperty("os.name")
        var currentDir = System.getProperty("user.dir")
        logger.info("osName: {} currentDir: {}", osName, currentDir)
        // MacOS 存放目录为用户目录
        if (osName.startsWith("Mac OS") && !currentDir.startsWith("/Users/")) {
            workDirPath = Paths.get(System.getProperty("user.home"), ".reader").toString()
        }
    }
    var path = Paths.get(workDirPath, subPath);

    return path.toString();
}

fun getStoragePath(): String {
    if (storageFinalPath.isNotEmpty()) {
        return storageFinalPath;
    }
    var appConfig = SpringContextUtils.getBean("appConfig", AppConfig::class.java)
    var storageDir = File("storage")
    if (appConfig != null) {
        // logger.info("storagePath from appConfig: {}", appConfig.storagePath)
        storageDir = File(appConfig.storagePath)
    }
    if (storageDir.isAbsolute()) {
        return storageDir.toString();
    }
    var storagePath = getWorkDir(storageDir.toString())
    if (appConfig != null) {
        storageFinalPath = storagePath
    }
    return storagePath;
}

fun saveStorage(name: String, value: Any) {
    val toJson: String = if (value is JsonObject || value is JsonArray) {
        value.toString()
    } else {
        gson.toJson(value)
    }

    var storagePath = getStoragePath()
    var storageDir = File(storagePath)
    if (!storageDir.exists()) {
        storageDir.mkdirs()
    }

    val file = File(storagePath + "/${name}.json")
    logger.info("storage key: {} path: {}", name, file.absoluteFile)

    if (!file.parentFile.exists()) {
        file.parentFile.mkdirs()
    }

    if (!file.exists()) {
        file.createNewFile()
    }
    file.writeText(toJson)
}

fun getStorage(name: String): String?  {
    var storagePath = getStoragePath()
    var storageDir = File(storagePath)
    if (!storageDir.exists()) {
        storageDir.mkdirs()
    }

    val file = File(storagePath + "/${name}.json")
    logger.info("storage key: {} path: {}", name, file.absoluteFile)
    if (!file.exists()) {
        return null
    }
    return file.readText()
}

fun asJsonArray(value: Any?): JsonArray? {
    if (value is JsonArray) {
        return value
    } else if (value is String) {
        return JsonArray(value)
    }
    return null
}

fun asJsonObject(value: Any?): JsonObject? {
    if (value is JsonObject) {
        return value
    } else if (value is String) {
        return JsonObject(value)
    }
    return null
}

//convert a data class to a map
fun <T> T.serializeToMap(): Map<String, Any> {
    return convert()
}

//convert string to a map
fun <T> T.toMap(): Map<String, Any> {
    return convert()
}

//convert a map to a data class
inline fun <reified T> Map<String, Any>.toDataClass(): T {
    return convert()
}

//convert an object of type I to type O
inline fun <I, reified O> I.convert(): O {
    val json = if (this is String) {
        this
    } else {
        gson.toJson(this)
    }
    return gson.fromJson(json, object : TypeToken<O>() {}.type)
}

@Suppress("UNCHECKED_CAST")
fun <R> readInstanceProperty(instance: Any, propertyName: String): R {
    val property = instance::class.memberProperties
                     // don't cast here to <Any, R>, it would succeed silently
                     .first { it.name == propertyName } as KProperty1<Any, *>
    // force a invalid cast exception if incorrect type here
    return property.get(instance) as R
}

@Suppress("UNCHECKED_CAST")
fun setInstanceProperty(instance: Any, propertyName: String, propertyValue: Any) {
    val property = instance::class.memberProperties
                     .first { it.name == propertyName }
    if(property is KMutableProperty<*>) {
        property.setter.call(instance, propertyValue)
    }
}

fun Book.fillData(newBook: Book, keys: List<String>): Book {
    keys.let {
        for (key in it) {
            var current = readInstanceProperty<String>(this, key)
            if (current.isNullOrEmpty()) {
                var cacheValue = readInstanceProperty<String>(newBook, key)
                if (!cacheValue.isNullOrEmpty()) {
                    setInstanceProperty(this, key, cacheValue)
                }
            }
        }
    }
    return this
}
