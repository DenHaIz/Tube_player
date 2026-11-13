package com.tube.strategy

import com.tube.byedpi.Probe
import com.tube.byedpi.StrategyTester

/**
 * Какими проверками судить о стратегии. [quick] — первый круг подбора (сотня стратегий, лёгкие проверки),
 * [full] — второй круг, ранний выбор и сторож (ближе всего к тому, что делает приложение).
 */
interface ProbePlan {
    suspend fun quick(videoHost: String?): List<Probe>
    suspend fun full(videoHost: String?): List<Probe> = quick(videoHost)

    companion object {
        /** VPN (Tube): трафик приложения YouTube — Chrome-ClientHello, поэтому и проверки с ним. */
        val Vpn: ProbePlan = object : ProbePlan {
            override suspend fun quick(videoHost: String?) = StrategyTester.defaultProbes(videoHost)
        }

        /**
         * Встроенный прокси (Tube Player): проверки тем же TLS, что у OkHttp приложения; во втором круге и у сторожа —
         * загрузка настоящего видеопотока ([video], null — не удалось получить ссылку, тогда ответ видеоузла).
         */
        fun app(video: suspend () -> Probe?): ProbePlan = object : ProbePlan {
            override suspend fun quick(videoHost: String?) = StrategyTester.appProbes(videoHost)
            override suspend fun full(videoHost: String?): List<Probe> {
                val probe = try {
                    video()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                return StrategyTester.appProbes(videoHost, probe)
            }
        }
    }
}
