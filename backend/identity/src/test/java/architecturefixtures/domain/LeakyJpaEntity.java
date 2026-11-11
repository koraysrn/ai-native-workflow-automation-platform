package architecturefixtures.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

/**
 * Deliberately violates the issue #199 exception: a JPA entity in a domain package that is not
 * named in {@code architecture/jpa-domain-exception.txt}. The negative test proves the ArchUnit
 * rule rejects it, so the exception cannot silently widen.
 */
@Entity
public class LeakyJpaEntity {

  @Id private UUID id = UUID.randomUUID();

  public UUID getId() {
    return id;
  }
}
