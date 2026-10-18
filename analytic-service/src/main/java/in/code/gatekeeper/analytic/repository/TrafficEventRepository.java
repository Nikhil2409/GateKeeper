package in.code.gatekeeper.analytic.repository;

import in.code.gatekeeper.analytic.document.TrafficEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

// Writes only; every read is an aggregation or a dynamic query through MongoTemplate in the services
public interface TrafficEventRepository extends MongoRepository<TrafficEventDocument, String> {
}
