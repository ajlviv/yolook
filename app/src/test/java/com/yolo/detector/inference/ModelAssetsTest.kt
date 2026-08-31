package com.yolo.detector.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAssetsTest {

    @Test
    fun isListed_trueWhenModelFilePresent() {
        val names = arrayOf("README.md", ModelAssets.FILE_NAME)
        assertTrue(ModelAssets.isListed(names))
    }

    @Test
    fun isListed_falseWhenModelFileMissing() {
        val names = arrayOf("README.md")
        assertFalse(ModelAssets.isListed(names))
    }

    @Test
    fun isListed_falseWhenAssetListIsNull() {
        assertFalse(ModelAssets.isListed(null))
    }
}
