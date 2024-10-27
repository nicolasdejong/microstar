package net.microstar.dispatcher.model;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.VersionComparator;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;

import java.util.Comparator;
import java.util.Optional;

/** Info on a specific service inside a service group */
@RequiredArgsConstructor @EqualsAndHashCode @ToString
public abstract class ServiceInfo {
    public static final Comparator<String>                 highestVersionFirstComparator = VersionComparator.NEWEST_TO_OLDEST;
    public static final Comparator<String>                  lowestVersionFirstComparator = VersionComparator.OLDEST_TO_NEWEST;
    public static final Comparator<ServiceInfo> serviceWithHighestVersionFirstComparator = Comparator.comparing(si -> si.id.version, highestVersionFirstComparator);
    public static final Comparator<ServiceInfo>  serviceWithLowestVersionFirstComparator = Comparator.comparing(si -> si.id.version,  lowestVersionFirstComparator);

    public final ServiceId id;
    public final Optional<JarInfo> jarInfo;
}
