package cloud.cleo.squareup.service;

import com.squareup.square.SquareClient;
import com.squareup.square.types.Customer;
import com.squareup.square.types.CustomerFilter;
import com.squareup.square.types.CustomerQuery;
import com.squareup.square.types.CustomerTextFilter;
import com.squareup.square.types.SearchCustomersRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class SquareCustomerService {

    /**
     * Optional Square client – will be null if Square is not enabled.
     */
    private final @Nullable SquareClient squareClient;

    public boolean isEnabled() {
        return squareClient != null;
    }

    /**
     * Find the first Square customer matching the given E.164 phone number.
     * @param e164Phone
     * @return 
     */
    public Optional<Customer> findCustomerByPhone(String e164Phone) {
        if (!isEnabled()) {
            log.debug("Square is not enabled; skipping customer lookup.");
            return Optional.empty();
        }
        if (e164Phone == null || e164Phone.isBlank()) {
            return Optional.empty();
        }

        try {
            var request = SearchCustomersRequest.builder()
                    .query(CustomerQuery.builder()
                            .filter(CustomerFilter.builder()
                                    .phoneNumber(CustomerTextFilter.builder()
                                            .exact(e164Phone)
                                            .build())
                                    .build())
                            .build())
                    .limit(1L)
                    .build();

            var response = squareClient
                    .customers()
                    .search(request);

            List<Customer> customers = response.getCustomers().orElse(List.of());
            if (customers.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(customers.get(0));
        } catch (CompletionException e) {
            log.error("Error in Square customer lookup (wrapped)", e.getCause());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error in Square customer lookup", e);
            return Optional.empty();
        }
    }

    /**
     * Convenience helper to get the customer's email by phone, if both exist.
     * @param e164Phone
     * @return 
     */
    public Optional<String> findCustomerEmailByPhone(String e164Phone) {
        return findCustomerByPhone(e164Phone)
                .flatMap(c -> c.getEmailAddress().orElse(null) == null
                        ? Optional.empty()
                        : Optional.of(c.getEmailAddress().get()))
                .filter(email -> !email.isBlank());
    }
}
