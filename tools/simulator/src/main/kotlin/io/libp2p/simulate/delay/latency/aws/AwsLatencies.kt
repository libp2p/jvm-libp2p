package io.libp2p.simulate.delay.latency.aws

import io.libp2p.simulate.delay.latency.ClusteredLatencyDistribution
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class AwsLatencies(latencyMatrixStr: String) {

    private val latencyMatrix: List<List<Int>> =
        latencyMatrixStr.lines().map { line ->
            line
                .split("\t")
                .map { it.toInt() }
        }

    fun getRoundtripLatency(fromAwsRegion: AwsRegion, toAwsRegion: AwsRegion): Duration =
        latencyMatrix[fromAwsRegion.ordinal][toAwsRegion.ordinal].milliseconds
    fun getLatency(fromAwsRegion: AwsRegion, toAwsRegion: AwsRegion): Duration =
        getRoundtripLatency(fromAwsRegion, toAwsRegion) / 2

    companion object {
        // thanks https://www.cloudping.co for the sample
        val latencyMatrixStrSample = """
            3	240	353	381	360	155	201	411	224	159	176	168	158	146	150	143	342	231	241	290	273
            241	1	46	37	39	87	36	126	186	189	211	180	211	204	197	121	299	188	175	145	136
            358	46	4	34	10	123	68	104	144	223	243	213	201	211	216	161	257	146	135	108	98
            385	38	33	2	24	126	70	142	172	223	241	212	229	238	244	156	285	174	162	128	118
            364	39	10	24	2	131	75	119	150	231	248	220	207	217	221	166	262	151	138	111	99
            162	91	126	124	131	1	54	145	186	110	131	102	120	111	104	35	298	185	194	228	218
            205	39	69	69	75	58	4	93	209	157	177	148	182	171	166	85	322	213	200	172	162
            412	128	109	141	118	146	93	1	198	249	294	238	256	264	279	176	312	198	187	138	140
            225	186	143	172	149	187	210	197	2	92	107	99	70	78	83	162	125	14	24	79	60
            162	189	223	223	230	110	158	249	92	2	22	12	26	17	12	87	204	91	102	151	141
            179	210	243	242	249	130	178	295	108	22	2	31	41	33	30	105	215	112	121	175	159
            171	179	214	214	220	101	150	238	101	11	31	1	35	25	20	77	212	101	110	160	151
            158	212	201	230	207	120	181	256	70	27	42	34	1	13	18	95	176	69	79	128	119
            148	202	210	238	216	111	171	265	78	17	32	25	13	1	9	87	186	76	86	147	128
            151	197	215	246	221	104	167	279	84	12	30	20	18	10	1	80	195	83	92	143	134
            147	122	162	159	168	35	87	179	163	85	105	78	96	87	79	1	271	160	170	220	212
            343	299	256	285	263	297	322	311	125	204	215	214	177	186	194	273	1	113	124	173	174
            232	188	146	174	150	186	211	199	15	92	111	102	70	76	82	160	114	4	13	62	64
            242	176	133	161	138	197	200	187	25	102	121	111	79	86	93	170	124	12	4	53	52
            292	146	108	128	109	227	171	139	79	151	173	160	128	147	142	220	171	62	50	3	22
            274	136	97	120	99	217	161	140	60	140	156	150	119	128	134	211	174	63	51	22	3
        """.trimIndent()

        val SAMPLE = AwsLatencies(latencyMatrixStrSample)
    }
}

