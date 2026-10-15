package com.bento.crm.payment.gateway;

import com.bento.crm.payment.model.PaymentSettings;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Looks up the gateway for a provider. */
@Component
public class PaymentGatewayRegistry {

    private final Map<PaymentSettings.Provider, PaymentGateway> gateways = new EnumMap<>(PaymentSettings.Provider.class);

    public PaymentGatewayRegistry(List<PaymentGateway> all) {
        all.forEach(g -> gateways.put(g.kind(), g));
    }

    public PaymentGateway get(PaymentSettings.Provider provider) {
        PaymentGateway gateway = gateways.get(provider);
        if (gateway == null) {
            throw new IllegalStateException("Online payment is not enabled");
        }
        return gateway;
    }

    public boolean available(PaymentSettings.Provider provider) {
        PaymentGateway gateway = gateways.get(provider);
        return gateway != null && gateway.configured();
    }
}
