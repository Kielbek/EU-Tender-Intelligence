-- Bulgaria adopted the euro on 1 January 2026 at the irrevocably fixed rate 1 EUR = 1.95583 BGN; the ECB no longer
-- publishes a BGN reference rate, so values in BGN were left without an EUR equivalent. Convert the existing ones
-- (new notices are converted by ExchangeRateService.FIXED_RATES).
UPDATE tenders SET estimated_value_eur = round(estimated_value / 1.95583, 2)
WHERE currency = 'BGN' AND estimated_value IS NOT NULL AND estimated_value_eur IS NULL;

UPDATE tender_results SET awarded_value_eur = round(awarded_value / 1.95583, 2)
WHERE currency = 'BGN' AND awarded_value IS NOT NULL AND awarded_value_eur IS NULL;
