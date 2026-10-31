package com.mediassistrover.backend.mapper;

import com.mediassistrover.backend.dto.voice.response.VoiceResponses.CommandResponse;
import com.mediassistrover.backend.dto.voice.response.VoiceResponses.InteractionResponse;
import com.mediassistrover.backend.entity.VoiceCommand;
import com.mediassistrover.backend.entity.VoiceInteraction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface VoiceMapper {
    @Mapping(target = "accountId", source = "account.accountId")
    @Mapping(target = "robotId", source = "robot.robotId")
    InteractionResponse toResponse(VoiceInteraction entity);

    @Mapping(target = "voiceInteractionId", source = "voiceInteraction.voiceInteractionId")
    @Mapping(target = "serviceRequestId", source = "serviceRequest.serviceRequestId")
    @Mapping(target = "parameters", source = "parametersJson")
    CommandResponse toResponse(VoiceCommand entity);
}
