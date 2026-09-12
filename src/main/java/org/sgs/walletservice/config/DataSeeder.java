package org.sgs.walletservice.config;

import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.repo.WalletRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSeeder {

    @Bean
    public CommandLineRunner seedDemoWallets(WalletRepository walletRepository) {
        return args -> {
            if (walletRepository.findByOwnerId("alice").isEmpty()) {
                walletRepository.save(new Wallet("alice", 100_000L)); // 1,000 INR in paise
            }
            if (walletRepository.findByOwnerId("bob").isEmpty()) {
                walletRepository.save(new Wallet("bob", 50_000L));   // 500 INR in paise
            }
        };
    }
}

