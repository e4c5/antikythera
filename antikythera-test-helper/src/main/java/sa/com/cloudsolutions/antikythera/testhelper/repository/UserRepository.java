package sa.com.cloudsolutions.antikythera.testhelper.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import sa.com.cloudsolutions.antikythera.testhelper.model.User;
import java.util.Collection;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    List<User> findByUsername(String username);
    List<User> findByActive(Boolean active);
    List<User> findByApprovalIdIn(Collection<Long> approvalIds);
    List<User> findByActiveOrderByCreatedDateDesc(Boolean active);
    List<User> findAllOrderByNameAsc();
    List<User> findAllOrderByLastNameAscFirstNameDesc();
    List<User> findByActiveNot(Boolean active);
    User findFirstByOrderByIdDesc();
    Long countByActive(Boolean active);
    void deleteByActive(Boolean active);
    boolean existsByUsername(String username);
    Long countByActiveAndAgeGreaterThan(Boolean active, Integer age);

    @Query("SELECT COUNT(u) FROM User u")
    Long countUsers();

    @Query("SELECT u FROM User u WHERE u.firstName IN :statuses")
    List<User> findByStatusIn(Collection<String> statuses);
}
