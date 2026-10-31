package com.mediassistrover.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;

@Entity @Table(name="DeliveryItem",uniqueConstraints=@UniqueConstraint(name="uq_delivery_item_code",columnNames={"delivery_request_id","item_code"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DeliveryItem {
    @Id @GeneratedValue @UuidGenerator @Column(name="delivery_item_id",columnDefinition="BINARY(16)") private UUID deliveryItemId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="delivery_request_id",nullable=false) private DeliveryRequest deliveryRequest;
    @Column(name="item_code",nullable=false,length=80) private String itemCode;
    @Column(name="item_name",nullable=false,length=160) private String itemName;
    @Column(name="item_category",nullable=false,length=64) private String itemCategory;
    @Column(name="quantity",nullable=false) private int quantity;
    @Column(name="unit",nullable=false,length=32) private String unit;
    @Column(name="is_sensitive",nullable=false) private boolean sensitive;
    @Column(name="handling_instruction",columnDefinition="TEXT") private String handlingInstruction;
    @Column(name="item_note",columnDefinition="TEXT") private String itemNote;
}
