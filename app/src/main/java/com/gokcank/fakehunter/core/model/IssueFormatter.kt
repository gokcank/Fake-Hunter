package com.gokcank.fakehunter.core.model

import android.content.Context
import com.gokcank.fakehunter.R

object IssueFormatter {

    fun resolveTitle(context: Context, issue: AuditIssue): String {
        val resId = when (issue.code) {
            IssueCode.STORAGE_VERIFICATION_FAILED -> R.string.issue_title_storage_failed
            IssueCode.STORAGE_TEST_INCOMPLETE -> R.string.issue_title_storage_incomplete
            IssueCode.RAM_SIZE_SPOOFED -> R.string.issue_title_ram_spoofed
            IssueCode.VIRTUAL_RAM_ACTIVE -> R.string.issue_title_virtual_ram
            IssueCode.HIGH_SWAP_ALLOCATION -> R.string.issue_title_high_swap
            IssueCode.ELEVATED_DISK_SWAP -> R.string.issue_title_disk_swap
            IssueCode.CPU_FREQ_RESTRICTED -> R.string.issue_title_cpu_restricted
            IssueCode.FAKE_CPU_CORES -> R.string.issue_title_fake_cores
            IssueCode.STORAGE_SIZE_SPOOFED -> R.string.issue_title_storage_spoofed
            IssueCode.CHIPSET_AC8227L_FAKE,
            IssueCode.CHIPSET_AC8227L_INFO -> R.string.issue_title_ac8227l
            IssueCode.CHIPSET_ALLWINNER_T3_FAKE,
            IssueCode.CHIPSET_ALLWINNER_T3_INFO -> R.string.issue_title_allwinner_t3
            IssueCode.CHIPSET_MT6580_FAKE,
            IssueCode.CHIPSET_MT6580_INFO -> R.string.issue_title_mt6580
            IssueCode.CHIPSET_ROCKCHIP_FAKE,
            IssueCode.CHIPSET_ROCKCHIP_INFO -> R.string.issue_title_rockchip
            IssueCode.ANDROID_VERSION_SPOOFED -> R.string.issue_title_android_spoofed
            null -> return issue.title
        }
        return context.getString(resId)
    }

    fun resolveDescription(context: Context, issue: AuditIssue): String {
        val a = issue.formatArgs
        return when (issue.code) {
            IssueCode.STORAGE_VERIFICATION_FAILED ->
                context.getString(R.string.issue_desc_storage_failed, a.getOrElse(0) { issue.description })
            IssueCode.STORAGE_TEST_INCOMPLETE ->
                context.getString(R.string.issue_desc_storage_incomplete, a.getOrElse(0) { issue.description })
            IssueCode.RAM_SIZE_SPOOFED ->
                context.getString(R.string.issue_desc_ram_spoofed, a.getOrElse(0) { "?" }, a.getOrElse(1) { "?" })
            IssueCode.VIRTUAL_RAM_ACTIVE ->
                context.getString(R.string.issue_desc_virtual_ram, a.getOrElse(0) { "?" }, a.getOrElse(1) { "?" }, a.getOrElse(2) { "?" })
            IssueCode.HIGH_SWAP_ALLOCATION ->
                context.getString(R.string.issue_desc_high_swap, a.getOrElse(0) { "?" }, a.getOrElse(1) { "?" }, a.getOrElse(2) { "?" })
            IssueCode.ELEVATED_DISK_SWAP ->
                context.getString(R.string.issue_desc_disk_swap, a.getOrElse(0) { "?" }, a.getOrElse(1) { "?" }, a.getOrElse(2) { "?" })
            IssueCode.CPU_FREQ_RESTRICTED ->
                context.getString(R.string.issue_desc_cpu_restricted, a.getOrElse(0) { "?" })
            IssueCode.FAKE_CPU_CORES ->
                context.getString(R.string.issue_desc_fake_cores, a.getOrElse(0) { "?" }, a.getOrElse(1) { "?" })
            IssueCode.STORAGE_SIZE_SPOOFED ->
                context.getString(R.string.issue_desc_storage_spoofed, a.getOrElse(0) { "?" }, a.getOrElse(1) { "?" })
            IssueCode.CHIPSET_AC8227L_FAKE -> context.getString(R.string.issue_desc_ac8227l_fake)
            IssueCode.CHIPSET_AC8227L_INFO -> context.getString(R.string.issue_desc_ac8227l_info)
            IssueCode.CHIPSET_ALLWINNER_T3_FAKE -> context.getString(R.string.issue_desc_allwinner_t3_fake)
            IssueCode.CHIPSET_ALLWINNER_T3_INFO -> context.getString(R.string.issue_desc_allwinner_t3_info)
            IssueCode.CHIPSET_MT6580_FAKE -> context.getString(R.string.issue_desc_mt6580_fake)
            IssueCode.CHIPSET_MT6580_INFO -> context.getString(R.string.issue_desc_mt6580_info)
            IssueCode.CHIPSET_ROCKCHIP_FAKE -> context.getString(R.string.issue_desc_rockchip_fake)
            IssueCode.CHIPSET_ROCKCHIP_INFO -> context.getString(R.string.issue_desc_rockchip_info)
            IssueCode.ANDROID_VERSION_SPOOFED ->
                context.getString(R.string.issue_desc_android_spoofed, a.getOrElse(0) { "?" }, a.getOrElse(1) { "?" }, a.getOrElse(2) { "?" })
            null -> issue.description
        }
    }
}
