-- Stage 5b+: Polish national notices (BZP) through the public e-Zamówienia API. The service is free and needs no
-- access request (UZP "Regulamin korzystania z API", §3 sec. 8). Status becomes ACTIVE after the first good import.
UPDATE data_sources
SET adapter_type = 'BZP_NOTICE_API_V1',
    status       = 'INACTIVE',
    homepage_url = 'https://ezamowienia.gov.pl/mo-client-board/bzp',
    terms_url    = 'https://media.ezamowienia.gov.pl/pod/2023/01/Regulamin-korzystania-z-API.pdf',
    notes        = 'Public BZP notice API: /mo-board/api/v1/notice (anonymous, max 500 per page, sliding window). '
                   || 'Below-EU-threshold Polish procedures; above-threshold ones are imported from TED.',
    updated_at   = now()
WHERE code = 'PL_EZAMOWIENIA';
