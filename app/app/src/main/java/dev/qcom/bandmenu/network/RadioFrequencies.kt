package dev.qcom.bandmenu.network

// LTE band plan and NR-ARFCN frequency maths, from the Shannon Band Menu
// (NetworkInfo.kt at 09f3463). Downlink EARFCN in, MHz out.

data class RadioFrequencies(
    val downlinkMhz: Double,
    val uplinkMhz: Double?
)

private data class LteBandPlan(
    val band: Int,
    val firstEarfcn: Int,
    val lastEarfcn: Int,
    val downlinkLowMhz: Double,
    val uplinkLowMhz: Double?
)

private val LTE_BAND_PLANS = listOf(
    LteBandPlan(1, 0, 599, 2110.0, 1920.0),
    LteBandPlan(2, 600, 1199, 1930.0, 1850.0),
    LteBandPlan(3, 1200, 1949, 1805.0, 1710.0),
    LteBandPlan(4, 1950, 2399, 2110.0, 1710.0),
    LteBandPlan(5, 2400, 2649, 869.0, 824.0),
    LteBandPlan(6, 2650, 2749, 875.0, 830.0),
    LteBandPlan(7, 2750, 3449, 2620.0, 2500.0),
    LteBandPlan(8, 3450, 3799, 925.0, 880.0),
    LteBandPlan(9, 3800, 4149, 1844.9, 1749.9),
    LteBandPlan(10, 4150, 4749, 2110.0, 1710.0),
    LteBandPlan(11, 4750, 4949, 1475.9, 1427.9),
    LteBandPlan(12, 5010, 5179, 729.0, 699.0),
    LteBandPlan(13, 5180, 5279, 746.0, 777.0),
    LteBandPlan(14, 5280, 5379, 758.0, 788.0),
    LteBandPlan(17, 5730, 5849, 734.0, 704.0),
    LteBandPlan(18, 5850, 5999, 860.0, 815.0),
    LteBandPlan(19, 6000, 6149, 875.0, 830.0),
    LteBandPlan(20, 6150, 6449, 791.0, 832.0),
    LteBandPlan(21, 6450, 6599, 1495.9, 1447.9),
    LteBandPlan(22, 6600, 7399, 3510.0, 3410.0),
    LteBandPlan(23, 7500, 7699, 2180.0, 2000.0),
    LteBandPlan(24, 7700, 8039, 1525.0, 1626.5),
    LteBandPlan(25, 8040, 8689, 1930.0, 1850.0),
    LteBandPlan(26, 8690, 9039, 859.0, 814.0),
    LteBandPlan(27, 9040, 9209, 852.0, 807.0),
    LteBandPlan(28, 9210, 9659, 758.0, 703.0),
    LteBandPlan(29, 9660, 9769, 717.0, null),
    LteBandPlan(30, 9770, 9869, 2350.0, 2305.0),
    LteBandPlan(31, 9870, 9919, 462.5, 452.5),
    LteBandPlan(32, 9920, 10359, 1452.0, null),
    LteBandPlan(33, 36000, 36199, 1900.0, 1900.0),
    LteBandPlan(34, 36200, 36349, 2010.0, 2010.0),
    LteBandPlan(35, 36350, 36949, 1850.0, 1850.0),
    LteBandPlan(36, 36950, 37549, 1930.0, 1930.0),
    LteBandPlan(37, 37550, 37749, 1910.0, 1910.0),
    LteBandPlan(38, 37750, 38249, 2570.0, 2570.0),
    LteBandPlan(39, 38250, 38649, 1880.0, 1880.0),
    LteBandPlan(40, 38650, 39649, 2300.0, 2300.0),
    LteBandPlan(41, 39650, 41589, 2496.0, 2496.0),
    LteBandPlan(42, 41590, 43589, 3400.0, 3400.0),
    LteBandPlan(43, 43590, 45589, 3600.0, 3600.0),
    LteBandPlan(44, 45590, 46589, 703.0, 703.0),
    LteBandPlan(46, 46790, 54539, 5150.0, 5150.0),
    LteBandPlan(48, 55240, 56739, 3550.0, 3550.0),
    LteBandPlan(65, 65536, 66435, 2110.0, 1920.0),
    LteBandPlan(66, 66436, 67335, 2110.0, 1710.0),
    LteBandPlan(67, 67336, 67535, 738.0, null),
    LteBandPlan(68, 67536, 67835, 753.0, 698.0),
    LteBandPlan(69, 67836, 68335, 2570.0, null),
    LteBandPlan(70, 68336, 68585, 1995.0, 1695.0),
    LteBandPlan(71, 68586, 68935, 617.0, 663.0),
    LteBandPlan(72, 68936, 68985, 461.0, 451.0),
    LteBandPlan(73, 68986, 69035, 460.0, 450.0),
    LteBandPlan(74, 69036, 69465, 1475.0, 1427.0),
    LteBandPlan(75, 69466, 70315, 1432.0, null),
    LteBandPlan(76, 70316, 70365, 1427.0, null),
    LteBandPlan(85, 70366, 70545, 728.0, 698.0),
    LteBandPlan(87, 70546, 70595, 420.0, 410.0),
    LteBandPlan(88, 70596, 70645, 422.0, 412.0)
)

fun lteBandForEarfcn(earfcn: Int): Int? =
    LTE_BAND_PLANS.firstOrNull { earfcn in it.firstEarfcn..it.lastEarfcn }?.band

fun lteFrequenciesForEarfcn(earfcn: Int): RadioFrequencies? {
    val plan = LTE_BAND_PLANS.firstOrNull { earfcn in it.firstEarfcn..it.lastEarfcn }
        ?: return null
    val offsetMhz = (earfcn - plan.firstEarfcn) / 10.0
    return RadioFrequencies(
        downlinkMhz = plan.downlinkLowMhz + offsetMhz,
        uplinkMhz = plan.uplinkLowMhz?.plus(offsetMhz)
    )
}

fun nrFrequencyForArfcn(arfcn: Int): Double? = when (arfcn) {
    in 0..599_999 -> arfcn * 0.005
    in 600_000..2_016_666 -> 3000.0 + (arfcn - 600_000) * 0.015
    in 2_016_667..3_279_165 -> 24_250.08 + (arfcn - 2_016_667) * 0.06
    else -> null
}
