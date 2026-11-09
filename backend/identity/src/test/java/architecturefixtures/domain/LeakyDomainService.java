package architecturefixtures.domain;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

/** Deliberately violates the domain purity rules; used only by the negative ArchUnit test. */
@Service
public class LeakyDomainService {

  private final EntityManager entityManager;

  public LeakyDomainService(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public Object load(Object id) {
    return entityManager.find(Object.class, id);
  }
}
