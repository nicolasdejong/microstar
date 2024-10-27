package net.microstar.dispatcher.model;

import lombok.EqualsAndHashCode;
import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;

import java.util.Optional;

/** This class represents a service jar that can be started but currently isn't */
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ServiceInfoJar extends ServiceInfo {
    public ServiceInfoJar(ServiceId id, JarInfo jar) {
        super(id, Optional.of(jar));
    }

    /** Throws if other has no jar file */
    public ServiceInfoJar(ServiceInfo other) {
        super(other.id, Optional.of(other.jarInfo.orElseThrow()));
    }

    public String toString() {
        return "ServiceInfoJar(" + jarInfo.map(jf->jf.name).orElse("<none>") + ")";
    }
}
