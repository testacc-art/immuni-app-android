/*
 * Copyright (C) 2020 Presidenza del Consiglio dei Ministri.
 * Please refer to the AUTHORS file for more information.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package it.ministerodellasalute.immuni.logic.exposure

import android.app.Activity
import android.content.Intent
import it.ministerodellasalute.immuni.api.services.ExposureIngestionService
import it.ministerodellasalute.immuni.extensions.nearby.ExposureNotificationClient
import it.ministerodellasalute.immuni.extensions.nearby.ExposureNotificationManager
import it.ministerodellasalute.immuni.logic.exposure.models.*
import it.ministerodellasalute.immuni.logic.exposure.repositories.ExposureIngestionRepository
import it.ministerodellasalute.immuni.logic.exposure.repositories.ExposureReportingRepository
import it.ministerodellasalute.immuni.logic.exposure.repositories.ExposureStatusRepository
import it.ministerodellasalute.immuni.logic.notifications.AppNotificationManager
import it.ministerodellasalute.immuni.logic.notifications.NotificationType
import it.ministerodellasalute.immuni.logic.settings.ConfigurationSettingsManager
import it.ministerodellasalute.immuni.logic.settings.models.ConfigurationSettings
import it.ministerodellasalute.immuni.logic.user.repositories.UserRepository
import java.io.File
import java.util.*
import kotlin.math.max
import kotlinx.coroutines.flow.StateFlow

class ExposureManager(
    private val settingsManager: ConfigurationSettingsManager,
    private val exposureNotificationManager: ExposureNotificationManager,
    private val userRepository: UserRepository,
    private val exposureReportingRepository: ExposureReportingRepository,
    private val exposureIngestionRepository: ExposureIngestionRepository,
    private val exposureStatusRepository: ExposureStatusRepository,
    private val appNotificationManager: AppNotificationManager
) : ExposureNotificationManager.Delegate {

    private val settings get() = settingsManager.settings.value

    val isBroadcastingActive: StateFlow<Boolean?> = exposureNotificationManager.isBroadcastingActive

    init {
        exposureNotificationManager.setup(this)
    }

    val exposureStatus = exposureStatusRepository.exposureStatus

    val lastSuccessfulCheckDate = exposureReportingRepository.lastSuccessfulCheckDate

    fun deviceSupportsLocationlessScanning() =
        exposureNotificationManager.deviceSupportsLocationlessScanning()

    suspend fun updateAndGetServiceIsActive(): Boolean {
        exposureNotificationManager.update()
        return exposureNotificationManager.areExposureNotificationsEnabled.value ?: false
    }

    override suspend fun processKeys(
        serverDate: Date,
        summary: ExposureNotificationClient.ExposureSummary,
        getInfos: suspend () -> List<ExposureNotificationClient.ExposureInformation>
    ) {
        val lastExposureDate = Calendar.getInstance().apply {
            time = serverDate
            add(Calendar.DAY_OF_YEAR, -summary.daysSinceLastExposure)
        }.time

        var summaryEntity =
            ExposureSummary(
                date = serverDate,
                lastExposureDate = lastExposureDate,
                matchedKeyCount = summary.matchedKeyCount,
                maximumRiskScore = summary.maximumRiskScore,
                highRiskAttenuationDurationMinutes = summary.highRiskAttenuationDurationMinutes,
                mediumRiskAttenuationDurationMinutes = summary.mediumRiskAttenuationDurationMinutes,
                lowRiskAttenuationDurationMinutes = summary.lowRiskAttenuationDurationMinutes,
                riskScoreSum = summary.riskScoreSum
            )

        val oldExposureStatus = exposureStatusRepository.exposureStatus.value

        val newExposureStatus = computeExposureStatus(summaryEntity, oldExposureStatus)

        if (shouldSendNotification(oldExposureStatus, newExposureStatus)) {
            exposureStatusRepository.setExposureStatus(newExposureStatus)
            val infos = getInfos()
            summaryEntity = summaryEntity.copy(
                exposureInfos = infos.map { it.repositoryExposureInformation }
            )
        }

        exposureReportingRepository.addSummary(summaryEntity)
    }

    private fun computeExposureStatus(
        summary: ExposureSummary,
        oldExposureStatus: ExposureStatus
    ): ExposureStatus {
        if (summary.matchedKeyCount == 0 || summary.maximumRiskScore < settings.exposureInfoMinimumRiskScore) {
            return oldExposureStatus
        }
        val oldStatusLastExposureTime =
            (oldExposureStatus as? ExposureStatus.Exposed)?.lastExposureDate?.time
        val maxLastExposureDate =
            Date(max(summary.lastExposureDate.time, oldStatusLastExposureTime ?: 0))

        return when (oldExposureStatus) {
            is ExposureStatus.Positive ->
                oldExposureStatus
            is ExposureStatus.Exposed ->
                ExposureStatus.Exposed(lastExposureDate = maxLastExposureDate)
            is ExposureStatus.None ->
                ExposureStatus.Exposed(lastExposureDate = maxLastExposureDate)
        }
    }

    private fun shouldSendNotification(old: ExposureStatus, new: ExposureStatus): Boolean {
        return when {
            old is ExposureStatus.None
                && new is ExposureStatus.Exposed -> {
                true
            }
            old is ExposureStatus.Exposed &&
                new is ExposureStatus.Exposed
                && new.lastExposureDate > old.lastExposureDate -> {
                true
            }
            else -> false
        }
    }

    suspend fun optInAndStartExposureTracing(activity: Activity) {
        stopExposureNotification()
        exposureNotificationManager.optInAndStartExposureTracing(activity)
        appNotificationManager.removeNotification(NotificationType.ServiceNotActive)
    }

    suspend fun stopExposureNotification() {
        exposureNotificationManager.stopExposureNotification()
    }

    suspend fun provideDiagnosisKeys(keyFiles: List<File>, token: String) {
        exposureNotificationManager.provideDiagnosisKeys(
            keyFiles = keyFiles,
            configuration = settings.exposureConfiguration.clientExposureConfiguration,
            token = token
        )
    }

    suspend fun startProcessingKeys(token: String, serverDate: Date) {
        exposureNotificationManager.processKeys(token, serverDate)
    }

    fun onRequestPermissionsResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        exposureNotificationManager.onRequestPermissionsResult(
            activity = activity,
            requestCode = requestCode,
            resultCode = resultCode,
            data = data
        )
    }

    suspend fun requestTekHistory(activity: Activity): List<ExposureNotificationClient.TemporaryExposureKey> {
        if (exposureNotificationManager.areExposureNotificationsEnabled.value != true) {
            exposureNotificationManager.optInAndStartExposureTracing(activity)
        }
        return exposureNotificationManager.requestTekHistory(activity)
    }

    suspend fun validateOtp(otp: String): OtpValidationResult {
        return exposureIngestionRepository.validateOtp(otp)
    }

    suspend fun validateCun(
        cun: String,
        healthInsuranceCard: String,
        symptom_onset_date: String?
    ): CunValidationResult {
        return exposureIngestionRepository.validateCun(cun, healthInsuranceCard, symptom_onset_date)
    }

    suspend fun getGreenCard(
        typeToken: String,
        token: String,
        healthInsurance: String,
        expiredHealthIDDate: String
    ): GreenPassValidationResult {
//        return GreenPassValidationResult.Success(
//            GreenPassToken(
//            greenPass = GreenCertificate(
//                expiredDate = expiredHealthIDDate,
//                base64 = "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 EAIAAIQSCAEAAEKtvffTNVxmrdU21snqdNZTpWrXVc29s883jtV5S3TWUzXWtDM4rebOfXjj2Ule i7ee5RvXvUpnDztNm5d6fjbtv818vhACAACEEggBAABCCYQAAAChBEIAAIBQAiEAAEAogRAAACCU QAgAABBKIAQAAAglEAIAAIQSCAEAAEIJhAAAAKHW3vvpGgZZa7WNVdX5k5pPxpo292n1nKiqubM/ VWPd2MMT0+rp1LnuJ6bdddPusRtNew9OM+3u7XxfTHvOiRvvlmlj8RRfCAEAAEIJhAAAAKEEQgAA gFACIQAAQCiBEAAAIJRACAAAEEogBAAACCUQAgAAhBIIAQAAQgmEAAAAoQRCAACAUJ+nC3invffT JfzLWuvpEv5YVQ9P5l7Vn5OaO9eis56T53Sei856Ovdq1ZqePGfaPValc72qdJ7lt94b0/pTdQan zavTtPfOjT2s0nkupq37O/hCCAAAEEogBAAACCUQAgAAhBIIAQAAQgmEAAAAoQRCAACAUAIhAABA KIEQAAAglEAIAAAQSiAEAAAIJRACAACEWnvvp2u4zFrr629Oulr1nCon9XS6cWfeuKZV9UzbP52q znuVzvunal43nvcTN/bZO26OG/vT+U6Z9v5667tg2lgn3vpO+Xt8IQQAAAglEAIAAIQSCAEAAEIJ hAAAAKEEQgAAgFACIQAAQCiBEAAAIJRACAAAEEogBAAACCUQAgAAhBIIAQAAQq2999M1vNBaq22s kxXsrOdE566rmntVzSf1vHWsTjeei7d661vmxrvlxLT1qrrHbnzOiWnvUz382bQennjrWPyTL4QA AAChBEIAAIBQAiEAAEAogRAAACCUQAgAABBKIAQAAAglEAIAAIQSCAEAAEIJhAAAAKEEQgAAgFAC IQAAQKi19366BkZYa339TdVuqRrr5Dk3qpp753NOTKu5c/9MW4sT087XtDU9Me3s3DhWp849duOd cOLGmqu89exMu3vfenae5QshAABAKIEQAAAglEAIAAAQSiAEAAAIJRACAACEEggBAABCCYQAAACh BEIAAIBQAiEAAEAogRAAACCUQAgAABDq83QB91lrff3N3rvkOSc6xzpRNVZVnzt19rlKVQ9vnPuJ qvP11v5UmXZnnrhxTTvv1c535Y0139ifE9POV2cPb7zHqkzbq9P6M58vhAAAAKEEQgAAgFACIQAA QCiBEAAAIJRACAAAEEogBAAACCUQAgAAhBIIAQAAQgmEAAAAoQRCAACAUAIhAABAqLX3frqGQdZa X39z0rGT55yYtjpV86pStRadfbY37vLWPlfNy535ezf2cNp6TZv7tHdK57zs556xTky756vGOnHj u+BZvhACAACEEggBAABCCYQAAAChBEIAAIBQAiEAAEAogRAAACCUQAgAABBKIAQAAAglEAIAAIQS CAEAAEIJhAAAAKE+Txcwy9571HPWWiXP6TRt7lX1nOis+WSszrnfaNperVK1f6bpnJcz+LNp9+pb 9/xb99iN5/TkOZ3rVdXDaTXfuBbv4AshAABAKIEQAAAglEAIAAAQSiAEAAAIJRACAACEEggBAABC CYQAAAChBEIAAIBQAiEAAEAogRAAACCUQAgAABDq83QB91lrlTxn713ym5N6qp5T5caaT5zUfGLa vKrW68axTkyrZ5ob51VVc+ddV/WcG89gZ803vps6VfW5SvL5OtG5n53ByXwhBAAACCUQAgAAhBII AQAAQgmEAAAAoQRCAACAUAIhAABAKIEQAAAglEAIAAAQSiAEAAAIJRACAACEEggBAABCrb330zUM stYqec5JV6vGqlJV87QddWPNnZL703neT1StxbS7pdNb12Laune+LzrvKOveo/Od8tZ7/q1jJf8n eZYvhAAAAKEEQgAAgFACIQAAQCiBEAAAIJRACAAAEEogBAAACCUQAgAAhBIIAQAAQgmEAAAAoQRC AACAUAIhAABAqM/TBdxn7/31N2utkuec6ByryknN01TV3LnuJ6r28406z0VVDzvXovOu6+zPST1V e6Nq7p19vvFOuPE9WOXGdZ+2n5NNuzN5ii+EAAAAoQRCAACAUAIhAABAKIEQAAAglEAIAAAQSiAE AAAIJRACAACEEggBAABCCYQAAAChBEIAAIBQAiEAAECotfd+uoZB1lpff1PVsZOxbtS5o966Xic1 d86901vPBb9XdS6Sx6pyY80nbrx7q+rpfE6Vaet1456fZtrdm8MXQgAAgFACIQAAQCiBEAAAIJRA CAAAEEogBAAACCUQAgAAhBIIAQAAQgmEAAAAoQRCAACAUAIhAABAKIEQAAAg1OfpAmbZez9dwh/r rHmtVfKbEyfzOvlNZz1VY1U5qadqXp37cNq6V0neq9Pu3s6zU6WqhzfWfKJzXsn7p0pVPW/t87T3 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 ejhtXidufO9M62GVzjv8ROcd1fluqjJtH97Yw2l377Szk8MXQgAAgFACIQAAQCiBEAAAIJRACAAA EEogBAAACCUQAgAAhBIIAQAAQgmEAAAAoQRCAACAUAIhAABAKIEQAAAg1Np7P13DZdZaJc+p6nxn PVVjnbhxZ05bi87nVLlxXp01n7jxLL+1h9bi98+pcuO7e1p/bvzf0unGNT1xY59PTJvXs3whBAAA CCUQAgAAhBIIAQAAQgmEAAAAoQRCAACAUAIhAABAKIEQAAAglEAIAAAQSiAEAAAIJRACAACEEggB AABCrb330zUMstZ6uoS/4mSVb5x71bze2p8TnT08Ma3P0/ZY54391nqm7bETb53XNG/t87T758a7 pcq0Hk6b+1vfp/P5QggAABBKIAQAAAglEAIAAIQSCAEAAEIJhAAAAKEEQgAAgFACIQAAQCiBEAAA IJRACAAAEEogBAAACCUQAgAAhFp776druMxa6+tvTrp68pxO02qetjOnzX3a/qlSNfeq/dM5VpVp e/VGbz1fJzrX9MazPO0/wLQ7s8pb+zNtXlWm9Zk/5QshAABAKIEQAAAglEAIAAAQSiAEAAAIJRAC AACEEggBAABCCYQAAAChBEIAAIBQAiEAAEAogRAAACCUQAgAABBq7b2frmGQtdbTJfzLjatT1cPO uZ/UfFJP1XNOdO7Vzpo7+9z5nBM37rHO/lR5a81Vps39xI3vyirT3rnT7sNO087pjfVM2885fCEE AAAIJRACAACEEggBAABCCYQAAAChBEIAAIBQAiEAAEAogRAAACCUQAgAABBKIAQAAAglEAIAAIQS CAEAAEJ9ni5glr13yXPWWiXPqVJVT1V/TkyruXNNp839RFXN087OjU562Lk3qpzUfDL3zj12Y80n qualPz0634Oda/rWd9zJvKbtsSrT9moOXwgBAABCCYQAAAChBEIAAIBQAiEAAEAogRAAACCUQAgA ABBKIAQAAAglEAIAAIQSCAEAAEIJhAAAAKEEQgAAgFCfpwuYZa319Td775LfnIx18psTJ/WcqOpP 53NO3FhzlWl7/sZ1v3GszudMG+vEjet1ovP+6bwTTnSO1WnaOZ32jqvy1v087b381v0zny+EAAAA oQRCAACAUAIhAABAKIEQAAAglEAIAAAQSiAEAAAIJRACAACEEggBAABCCYQAAAChBEIAAIBQAiEA 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 +7RvXzFtz7/RG+/VaU6O+Y3/t5l2zzOZXwgBAACiBCEAAECUIAQAAIgShAAAAFGCEAAAIEoQAgAA RAlCAACAKEEIAAAQJQgBAACiBCEAAECUIAQAAIi67vt+egyDXNf19BAes7ITVuZn146atha75mfX u1aU12vFtNvvjXN40lfvqGl3y645nLYW08Y8zVfvn2nnfZeT98aKaeNZ8dWz/N/4hRAAACBKEAIA AEQJQgAAgChBCAAAECUIAQAAogQhAABAlCAEAACIEoQAAABRghAAACBKEAIAAEQJQgAAgKjrvu+n xwAAAMAD/EIIAAAQJQgBAACiBCEAAECUIAQAAIgShAAAAFGCEAAAIEoQAgAARAlCAACAKEEIAAAQ JQgBAACiBCEAAECUIAQAAIgShAAAAFGCEAAAIEoQAgAARAlCAACAKEEIAAAQJQgBAACiBCEAAECU IAQAAIgShAAAAFGCEAAAIEoQAgAARAlCAACAKEEIAAAQJQgBAACiBCEAAECUIAQAAIgShAAAAFGC EAAAIEoQAgAARAlCAACAKEEIAAAQJQgBAACiBCEAAECUIAQAAIgShAAAAFGCEAAAIEoQAgAARAlC AACAKEEIAAAQJQgBAACiBCEAAECUIAQAAIgShAAAAFGCEAAAIEoQAgAARAlCAACAKEEIAAAQJQgB AACiBCEAAECUIAQAAIgShAAAAFGCEAAAIEoQAgAARAlCAACAKEEIAAAQJQgBAACiBCEAAECUIAQA AIgShAAAAFGCEAAAIEoQAgAARAlCAACAKEEIAAAQJQgBAACiBCEAAECUIAQAAIgShAAAAFGCEAAA IEoQAgAARAlCAACAKEEIAAAQJQgBAACiBCEAAECUIAQAAIgShAAAAFGCEAAAIEoQAgAARAlCAACA KEEIAAAQJQgBAACiBCEAAECUIAQAAIgShAAAAFGCEAAAIEoQAgAARAlCAACAKEEIAAAQ9Q975KF1 1jX9GQAAAABJRU5ErkJggg=="
//            ),
//                serverDate = Date()
//        ))
        return exposureIngestionRepository.getGreenCard(
            typeToken,
            token,
            healthInsurance,
            expiredHealthIDDate
        )
    }

    suspend fun dummyUpload(): Boolean {
        return exposureIngestionRepository.dummyUpload()
    }

    suspend fun uploadTeks(activity: Activity, token: OtpToken?, cun: CunToken?): Boolean {
        val tekHistory = requestTekHistory(activity)

        val exposureSummaries = exposureReportingRepository.getSummaries()

        val countriesOfInterest =
            exposureReportingRepository.getCountriesOfInterest().map { it.code }

        val isSuccess = exposureIngestionRepository.uploadTeks(
            token = token,
            cun = cun,
            province = userRepository.user.value!!.province,
            tekHistory = tekHistory.map { it.serviceTemporaryExposureKey },
            exposureSummaries = exposureSummaries.prepareForUpload(
                settings,
                token?.serverDate ?: cun!!.serverDate!!
            ),
            countries = countriesOfInterest
        )

        if (isSuccess) {
            exposureStatusRepository.setExposureStatus(ExposureStatus.Positive())
        }

        return isSuccess
    }

    fun resetExposureStatus() {
        exposureStatusRepository.resetExposureStatus()
        exposureStatusRepository.mockExposureStatus = null
    }

    fun acknowledgeExposure() {
        val exposureStatus = exposureStatus.value
        if (exposureStatus is ExposureStatus.Exposed && !exposureStatus.acknowledged) {
            exposureStatusRepository.setExposureStatus(exposureStatus.copy(acknowledged = true))
        }
    }

    fun setMockExposureStatus(status: ExposureStatus?) {
        exposureStatusRepository.mockExposureStatus = status
    }

    fun debugCleanupDatabase() {
        exposureReportingRepository.resetSummaries()
        exposureReportingRepository.setLastProcessedChunk(null)
        exposureReportingRepository.setCountriesOfInterest(listOf())
    }

    val hasSummaries: Boolean get() = exposureReportingRepository.getSummaries().isNotEmpty()
}

fun List<ExposureSummary>.prepareForUpload(
    settings: ConfigurationSettings,
    serverDate: Date
): List<ExposureIngestionService.ExposureSummary> {
    val exposureSummaries = this
        .sortedByDescending { it.date }
        .take(settings.teksMaxSummaryCount)

    val infos = exposureSummaries
        .mapIndexed { index, summary ->
            summary.exposureInfos.map { Pair(index, it) }
        }
        .flatten()
        .sortedWith(Comparator { (_, a), (_, b) ->
            // SORT BY `totalRiskScore` DESC, `date` ASC
            val riskComparison = b.totalRiskScore.compareTo(a.totalRiskScore)
            if (riskComparison == 0) a.date.compareTo(b.date) else riskComparison
        })
        .take(settings.teksMaxInfoCount)

    return exposureSummaries.mapIndexed { index, summary ->
        val summaryInfos = infos
            .filter { it.first == index }
            .map { it.second }

        summary
            .copy(exposureInfos = summaryInfos)
            .serviceExposureSummary(serverDate = serverDate)
    }
}
