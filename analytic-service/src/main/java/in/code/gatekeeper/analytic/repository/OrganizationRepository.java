package in.code.gatekeeper.analytic.repository;

import in.code.gatekeeper.analytic.document.OrganizationDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrganizationRepository extends MongoRepository<OrganizationDocument, Long> {
}
