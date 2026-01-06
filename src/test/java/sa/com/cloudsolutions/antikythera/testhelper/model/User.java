package sa.com.cloudsolutions.antikythera.testhelper.model;

import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.Id;

@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id;
    private String username;
    private String password;
    private Boolean active;
    private Integer age;
    private String firstName;
    private String lastName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
