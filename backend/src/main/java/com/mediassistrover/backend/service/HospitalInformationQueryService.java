package com.mediassistrover.backend.service;

import java.time.Instant;
import java.util.UUID;

public interface HospitalInformationQueryService {
    InformationAnswer query(String question, String languageCode);

    record InformationAnswer(boolean found, String answer, String fallbackCode, Source source) { }
    record Source(UUID articleId, String articleCode, String category, String title,
                  String languageCode, Instant updatedAt) { }
}
