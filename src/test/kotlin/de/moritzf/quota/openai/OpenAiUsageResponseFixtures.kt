package de.moritzf.quota.openai

import de.moritzf.quota.shared.JsonSupport
import org.intellij.lang.annotations.Language

/**
 * Anonymized real-world Codex usage API payloads used as regression fixtures.
 */
object OpenAiUsageResponseFixtures {
    const val WORKSPACE_ACCOUNT_ID = "account-anon-workspace-1"

    /**
     * Business usage-based workspace member with assigned credits available.
     * Original shape: no rate_limit windows, credits.has_credits=true.
     */
    @Language("JSON")
    val BUSINESS_MEMBER_WITH_ASSIGNED_CREDITS: String = """
        {
          "user_id": "user-anon-business-member-1",
          "account_id": "account-anon-workspace-1",
          "email": "member.with.credits@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": true,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": false,
            "individual_limit": null
          },
          "rate_limit_reached_type": null,
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Business usage-based workspace member with assigned credits depleted.
     * Original shape: no rate_limit windows, credits.has_credits=false,
     * rate_limit_reached_type=workspace_member_credits_depleted.
     */
    @Language("JSON")
    val BUSINESS_MEMBER_ASSIGNED_CREDITS_DEPLETED: String = """
        {
          "user_id": "user-anon-business-member-2",
          "account_id": "account-anon-workspace-1",
          "email": "member.without.credits@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": false,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": false,
            "individual_limit": null
          },
          "rate_limit_reached_type": {
            "type": "workspace_member_credits_depleted",
            "details": null
          },
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Plus subscriber with normal rate-limit windows and zero purchased credits.
     * Original shape: credits.has_credits=false, balance="0",
     * approx_*_messages returned as [0, 0] arrays.
     */
    @Language("JSON")
    val PLUS_WITH_RATE_LIMITS_AND_ZERO_PURCHASED_CREDITS: String = """
        {
          "user_id": "user-anon-plus-1",
          "account_id": "user-anon-plus-1",
          "email": "user@example.com",
          "plan_type": "plus",
          "rate_limit": {
            "allowed": true,
            "limit_reached": false,
            "primary_window": {
              "used_percent": 1,
              "limit_window_seconds": 18000,
              "reset_after_seconds": 18000,
              "reset_at": 1780353357
            },
            "secondary_window": {
              "used_percent": 1,
              "limit_window_seconds": 604800,
              "reset_after_seconds": 558034,
              "reset_at": 1780893390
            }
          },
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": false,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": "0",
            "approx_local_messages": [0, 0],
            "approx_cloud_messages": [0, 0]
          },
          "spend_control": {
            "reached": false,
            "individual_limit": null
          },
          "rate_limit_reached_type": null,
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Free account with a single weekly primary window.
     * Public source: basketikun/chatgpt2api#202. Original shape:
     * plan_type=free, primary_window only, secondary_window=null,
     * credits.balance=null, approx_*_messages=null.
     */
    @Language("JSON")
    val FREE_WITH_WEEKLY_RATE_LIMIT: String = """
        {
          "user_id": "user-anon-free-1",
          "account_id": "user-anon-free-1",
          "email": "free.user@example.com",
          "plan_type": "free",
          "rate_limit": {
            "allowed": true,
            "limit_reached": false,
            "primary_window": {
              "used_percent": 3,
              "limit_window_seconds": 604800,
              "reset_after_seconds": 604800,
              "reset_at": 1780634024
            },
            "secondary_window": null
          },
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": false,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": false,
            "individual_limit": null
          },
          "rate_limit_reached_type": null,
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * ProLite account payload that broke strict plan enum decoders.
     * Public source: steipete/CodexBar#709. Original shape includes
     * additional_rate_limits and omits rate_limit_reached_type,
     * referral_beacon, and rate_limit_reset_credits.
     */
    @Language("JSON")
    val PROLITE_WITH_ADDITIONAL_RATE_LIMITS: String = """
        {
          "user_id": "user-anon-prolite-1",
          "account_id": "user-anon-prolite-1",
          "email": "prolite.user@example.com",
          "plan_type": "prolite",
          "rate_limit": {
            "allowed": true,
            "limit_reached": false,
            "primary_window": {
              "used_percent": 12,
              "limit_window_seconds": 18000,
              "reset_after_seconds": 8581,
              "reset_at": 1776111121
            },
            "secondary_window": {
              "used_percent": 2,
              "limit_window_seconds": 604800,
              "reset_after_seconds": 569914,
              "reset_at": 1776672455
            }
          },
          "code_review_rate_limit": null,
          "additional_rate_limits": [
            {
              "limit_name": "GPT-5.3-Codex-Spark",
              "metered_feature": "codex_bengalfox",
              "rate_limit": {
                "allowed": true,
                "limit_reached": false,
                "primary_window": {
                  "used_percent": 0,
                  "limit_window_seconds": 18000,
                  "reset_after_seconds": 18000,
                  "reset_at": 1776120541
                },
                "secondary_window": {
                  "used_percent": 0,
                  "limit_window_seconds": 604800,
                  "reset_after_seconds": 604800,
                  "reset_at": 1776707341
                }
              }
            }
          ],
          "credits": {
            "has_credits": false,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": "0",
            "approx_local_messages": [0, 0],
            "approx_cloud_messages": [0, 0]
          },
          "spend_control": {
            "reached": false
          },
          "promo": null
        }
    """.trimIndent()

    /**
     * Business usage-based workspace owner whose workspace credits are depleted.
     * Publicly confirmed rate_limit_reached_type from openai/codex#24114.
     */
    @Language("JSON")
    val BUSINESS_OWNER_CREDITS_DEPLETED: String = """
        {
          "user_id": "user-anon-business-owner-1",
          "account_id": "account-anon-workspace-1",
          "email": "owner.credits.depleted@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": false,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": false,
            "individual_limit": null
          },
          "rate_limit_reached_type": {
            "type": "workspace_owner_credits_depleted",
            "details": null
          },
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Business usage-based workspace owner blocked by the workspace spend cap.
     * Publicly confirmed rate_limit_reached_type from openai/codex#24114.
     */
    @Language("JSON")
    val BUSINESS_OWNER_USAGE_LIMIT_REACHED: String = """
        {
          "user_id": "user-anon-business-owner-2",
          "account_id": "account-anon-workspace-1",
          "email": "owner.usage.limit@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": true,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": true,
            "individual_limit": null
          },
          "rate_limit_reached_type": {
            "type": "workspace_owner_usage_limit_reached",
            "details": null
          },
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Business usage-based workspace member blocked by the owner-set spend cap.
     * Publicly confirmed rate_limit_reached_type from openai/codex#24114.
     */
    @Language("JSON")
    val BUSINESS_MEMBER_USAGE_LIMIT_REACHED: String = """
        {
          "user_id": "user-anon-business-member-3",
          "account_id": "account-anon-workspace-1",
          "email": "member.usage.limit@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": true,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": true,
            "individual_limit": null
          },
          "rate_limit_reached_type": {
            "type": "workspace_member_usage_limit_reached",
            "details": null
          },
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Inferred: business usage-based workspace member with assigned credits
     * available AND a non-zero balance/approx message ranges. Field set mirrors
     * the captured business fixtures and the Plus shape for credit fields.
     */
    @Language("JSON")
    val BUSINESS_MEMBER_WITH_ASSIGNED_CREDITS_AND_BALANCE: String = """
        {
          "user_id": "user-anon-business-member-4",
          "account_id": "account-anon-workspace-1",
          "email": "member.with.balance@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": true,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": "125.50",
            "approx_local_messages": [0, 0],
            "approx_cloud_messages": [1, 5]
          },
          "spend_control": {
            "reached": false,
            "individual_limit": null
          },
          "rate_limit_reached_type": null,
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Inferred: business usage-based workspace member with unlimited credits
     * (i.e. an explicit unlimited pool, not yet depleted). Mirrors the
     * captured business shape; only credits.unlimited differs.
     */
    @Language("JSON")
    val BUSINESS_MEMBER_WITH_UNLIMITED_CREDITS: String = """
        {
          "user_id": "user-anon-business-member-5",
          "account_id": "account-anon-workspace-1",
          "email": "member.unlimited@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": true,
            "unlimited": true,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": false,
            "individual_limit": null
          },
          "rate_limit_reached_type": null,
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Inferred: business usage-based workspace member blocked by an individual
     * spend cap (per-member limit, not a workspace-wide cap). Field set mirrors
     * the captured business shape; individual_limit is a non-null number.
     */
    @Language("JSON")
    val BUSINESS_MEMBER_INDIVIDUAL_SPEND_LIMIT_REACHED: String = """
        {
          "user_id": "user-anon-business-member-6",
          "account_id": "account-anon-workspace-1",
          "email": "member.individual.limit@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": true,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": "10.00",
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": true,
            "individual_limit": 50.0
          },
          "rate_limit_reached_type": null,
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Inferred: business usage-based workspace owner whose overage limit was
     * hit (workspace still has credits, but the configured overage cap is
     * reached). Mirrors the captured business shape.
     */
    @Language("JSON")
    val BUSINESS_OWNER_OVERAGE_LIMIT_REACHED: String = """
        {
          "user_id": "user-anon-business-owner-3",
          "account_id": "account-anon-workspace-1",
          "email": "owner.overage.limit@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": true,
            "unlimited": false,
            "overage_limit_reached": true,
            "balance": "0",
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": true,
            "individual_limit": null
          },
          "rate_limit_reached_type": null,
          "promo": null,
          "referral_beacon": null,
          "rate_limit_reset_credits": {
            "available_count": 0
          }
        }
    """.trimIndent()

    /**
     * Inferred robustness fixture: business usage-based payload with the
     * same shape the public prolite body uses (omits rate_limit_reached_type,
     * referral_beacon, and rate_limit_reset_credits) to assert the decoder
     * stays lenient on optional fields.
     */
    @Language("JSON")
    val BUSINESS_MEMBER_OMITTING_OPTIONAL_FIELDS: String = """
        {
          "user_id": "user-anon-business-member-7",
          "account_id": "account-anon-workspace-1",
          "email": "member.minimal.payload@example.com",
          "plan_type": "self_serve_business_usage_based",
          "rate_limit": null,
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": true,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": false
          },
          "promo": null
        }
    """.trimIndent()

    fun deserialize(json: String): OpenAiCodexQuota {
        return JsonSupport.json.decodeFromString(json)
    }

    fun businessMemberWithAssignedCredits(): OpenAiCodexQuota =
        deserialize(BUSINESS_MEMBER_WITH_ASSIGNED_CREDITS)

    fun businessMemberAssignedCreditsDepleted(): OpenAiCodexQuota =
        deserialize(BUSINESS_MEMBER_ASSIGNED_CREDITS_DEPLETED)

    fun plusWithRateLimitsAndZeroPurchasedCredits(): OpenAiCodexQuota =
        deserialize(PLUS_WITH_RATE_LIMITS_AND_ZERO_PURCHASED_CREDITS)

    fun freeWithWeeklyRateLimit(): OpenAiCodexQuota =
        deserialize(FREE_WITH_WEEKLY_RATE_LIMIT)

    fun proliteWithAdditionalRateLimits(): OpenAiCodexQuota =
        deserialize(PROLITE_WITH_ADDITIONAL_RATE_LIMITS)

    fun businessOwnerCreditsDepleted(): OpenAiCodexQuota =
        deserialize(BUSINESS_OWNER_CREDITS_DEPLETED)

    fun businessOwnerUsageLimitReached(): OpenAiCodexQuota =
        deserialize(BUSINESS_OWNER_USAGE_LIMIT_REACHED)

    fun businessMemberUsageLimitReached(): OpenAiCodexQuota =
        deserialize(BUSINESS_MEMBER_USAGE_LIMIT_REACHED)

    fun businessMemberWithAssignedCreditsAndBalance(): OpenAiCodexQuota =
        deserialize(BUSINESS_MEMBER_WITH_ASSIGNED_CREDITS_AND_BALANCE)

    fun businessMemberWithUnlimitedCredits(): OpenAiCodexQuota =
        deserialize(BUSINESS_MEMBER_WITH_UNLIMITED_CREDITS)

    /**
     * Live team shape (2026-07): rate windows present and spend_control.individual_limit is an
     * object with string money fields + used_percent (not a bare number).
     */
    @Language("JSON")
    val TEAM_WITH_OBJECT_INDIVIDUAL_SPEND_LIMIT: String = """
        {
          "user_id": "user-anon-team-1",
          "account_id": "account-anon-team-1",
          "email": "member.team@example.com",
          "plan_type": "team",
          "rate_limit": {
            "allowed": true,
            "limit_reached": false,
            "primary_window": {
              "used_percent": 18,
              "limit_window_seconds": 604800,
              "reset_after_seconds": 515525,
              "reset_at": 1785299212
            },
            "secondary_window": null
          },
          "code_review_rate_limit": null,
          "additional_rate_limits": null,
          "credits": {
            "has_credits": false,
            "unlimited": false,
            "overage_limit_reached": false,
            "balance": null,
            "approx_local_messages": null,
            "approx_cloud_messages": null
          },
          "spend_control": {
            "reached": true,
            "individual_limit": {
              "source": "account_user_spend_controls",
              "limit": "10",
              "used": "10.721666693687439",
              "remaining": "0",
              "used_percent": 107,
              "remaining_percent": 0,
              "reset_after_seconds": 758713,
              "reset_at": 1785542400
            }
          },
          "rate_limit_reached_type": null,
          "promo": null,
          "rate_limit_reset_credits": {
            "available_count": 0,
            "applicable_available_count": 0
          }
        }
    """.trimIndent()

    fun businessMemberIndividualSpendLimitReached(): OpenAiCodexQuota =
        deserialize(BUSINESS_MEMBER_INDIVIDUAL_SPEND_LIMIT_REACHED)

    fun teamWithObjectIndividualSpendLimit(): OpenAiCodexQuota =
        deserialize(TEAM_WITH_OBJECT_INDIVIDUAL_SPEND_LIMIT)

    fun businessOwnerOverageLimitReached(): OpenAiCodexQuota =
        deserialize(BUSINESS_OWNER_OVERAGE_LIMIT_REACHED)

    fun businessMemberOmittingOptionalFields(): OpenAiCodexQuota =
        deserialize(BUSINESS_MEMBER_OMITTING_OPTIONAL_FIELDS)
}
