package com.bigmoe.onedge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTopologyTest {
    @Test
    fun defaultIsTheFirstTwoAndTheLastTwoCores() {
        assertEquals(0b11000011L, CpuTopology.defaultMask(8))
        assertEquals(0b110011L, CpuTopology.defaultMask(6))
        assertEquals(0b11011L, CpuTopology.defaultMask(5))
    }

    @Test
    fun smallPhonesUseEveryCore() {
        assertEquals(0b1111L, CpuTopology.defaultMask(4))
        assertEquals(0b1L, CpuTopology.defaultMask(1))
    }
}
