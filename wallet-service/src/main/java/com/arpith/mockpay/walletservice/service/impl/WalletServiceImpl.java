package com.arpith.mockpay.walletservice.service.impl;

import com.arpith.mockpay.walletservice.constant.Constant;
import com.arpith.mockpay.walletservice.constant.DataFieldKeys;
import com.arpith.mockpay.walletservice.constant.Topic;
import com.arpith.mockpay.walletservice.enumeration.Status;
import com.arpith.mockpay.walletservice.model.Wallet;
import com.arpith.mockpay.walletservice.repository.WalletRepository;
import com.arpith.mockpay.walletservice.service.WalletService;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {
    private static final Logger LOG = LoggerFactory.getLogger(WalletServiceImpl.class);
    private final WalletRepository walletRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Gson gson;

    @Value("${application.wallet.currency-type}")
    public String currencyType;

    @Value("${application.wallet.start-balance}")
    public Long initialBalance;

    @Override
    @KafkaListener(topics = {Topic.APP_USER_CREATED}, groupId = Constant.CONSUMER_GROUP_ID)
    public void createWallet(String message) {
        LOG.info("Entering WalletService.createWallet");
        Map<String, Object> appUserResponse = gson.fromJson(message, new TypeToken<HashMap<String, Object>>() {
        }.getType());
        try {
            var walletId = (String) appUserResponse.get(DataFieldKeys.EMAIL);
            walletRepository.findByWalletId(walletId).ifPresentOrElse(wallet -> publishEvent(appUserResponse, Status.FAILED, Topic.WALLET_CREATED), () -> {
                var wallet = Wallet.builder()
                        .walletId(walletId)
                        .currency(currencyType)
                        .balance(initialBalance)
                        .build();
                walletRepository.save(wallet);
                publishEvent(appUserResponse, Status.SUCCESSFUL, Topic.WALLET_CREATED);
            });
        } catch (Exception e) {
            publishEvent(appUserResponse, Status.FAILED, Topic.WALLET_CREATED);
        }
    }

    @Override
    @KafkaListener(topics = {Topic.TRANSACTION_CREATED}, groupId = Constant.CONSUMER_GROUP_ID)
    public void updateWallets(String message) {
        LOG.info("Entering WalletService.updateWallets");
        Map<String, Object> transactionMap = gson.fromJson(message, new TypeToken<HashMap<String, Object>>() {
        }.getType());
        try {
            var receiverId = (String) transactionMap.get(DataFieldKeys.RECEIVER_ID);
            var senderId = (String) transactionMap.get(DataFieldKeys.SENDER_ID);
            var amount = ((Double) transactionMap.get(DataFieldKeys.AMOUNT)).longValue();

            var senderWallet = walletRepository.findByWalletId(senderId);
            var receiverWallet = walletRepository.findByWalletId(receiverId);

            if (senderWallet.isPresent() && receiverWallet.isPresent() && senderWallet.get().getBalance() >= amount) {
                walletRepository.debitWallet(senderId, amount);
                walletRepository.creditWallet(receiverId, amount);
                publishEvent(transactionMap, Status.SUCCESSFUL, Topic.WALLET_UPDATED);
            } else {
                publishEvent(transactionMap, Status.FAILED, Topic.WALLET_UPDATED);
            }
        } catch (Exception e) {
            publishEvent(transactionMap, Status.FAILED, Topic.WALLET_UPDATED);
        }
    }

    @Override
    @KafkaListener(topics = {Topic.APP_USER_DELETED}, groupId = Constant.CONSUMER_GROUP_ID)
    public void deleteWallet(String message) throws JsonSyntaxException {
        LOG.info("Entering WalletService.deleteWallet");
        Map<String, Object> appUserMap = gson.fromJson(message, new TypeToken<HashMap<String, Object>>() {
        }.getType());
        try {
            var email = (String) appUserMap.get(DataFieldKeys.EMAIL);
            walletRepository.deleteByWalletId(email);
            publishEvent(appUserMap, Status.SUCCESSFUL, Topic.WALLET_DELETED);
        } catch (Exception e) {
            publishEvent(appUserMap, Status.FAILED, Topic.WALLET_DELETED);
        }
    }

    private void publishEvent(Map<String, Object> payload, Status status, String topic) {
        LOG.info("Entering WalletService.publishEvent");
        payload.put(DataFieldKeys.STATUS, status.name());
        kafkaTemplate.send(topic, gson.toJson(payload));
    }

}
