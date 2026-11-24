package io.maretha.asset.usn;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.transaction.TransactionHelper;
import com.google.inject.Inject;
import io.maretha.asset.usn.service.UsnService;

/** Release gate for the first externally visible write of a copied folder's child. */
@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("io.maretha.asset.usn.core")
@Deploy("io.maretha.asset.usn.tests:OSGI-INF/observe-copy.xml")
public class TestNativeCopyVisibility {
    private record VisiblePair(String text, Long number) {}
    private static final AtomicReference<VisiblePair> OBSERVED = new AtomicReference<>();

    public static class Observer implements EventListener {
        @Override
        public void handleEvent(Event event) {
            var ctx = (DocumentEventContext) event.getContext();
            var root = ctx.getSourceDocument();
            if (!"copy-visibility-destination".equals(root.getName())) return;
            var child = new PathRef(root.getPathAsString() + "/numbered");
            String repository = root.getRepositoryName();
            TransactionHelper.runWithoutTransaction(() -> TransactionHelper.runInTransaction(() -> {
                DocumentModel leaf = CoreInstance.getCoreSessionSystem(repository).getDocument(child);
                OBSERVED.set(new VisiblePair((String) leaf.getPropertyValue("usn:usn_string"),
                        (Long) leaf.getPropertyValue("usn:usn_long")));
            }));
        }
    }

    @Inject protected CoreSession session;
    @Inject protected UsnService service;

    @Test
    public void firstVisibleCopyOfNumberedChildMustBeUnnumbered() {
        assumeTrue(Boolean.getBoolean("usn.test.copyVisibility") && "mongodb".equals(System.getProperty("nuxeo.test.core")));
        OBSERVED.set(null);
        var folder = session.createDocument(session.createDocumentModel("/", "copy-visibility-source", "Folder"));
        var leaf = session.createDocumentModel(folder.getPathAsString(), "numbered", "Asset");
        leaf.setPropertyValue("usn:usn_date", service.toUtcNoonCalendar(LocalDate.of(2030, 1, 16)));
        leaf.setPropertyValue("usn:generate_usn", true);
        assertNotNull(session.createDocument(leaf).getPropertyValue("usn:usn_long"));
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();
        session.copy(folder.getRef(), session.getRootDocument().getRef(), "copy-visibility-destination");
        assertNotNull("Independent observer did not read the copied child", OBSERVED.get());
        assertEquals("First persisted destination values", new VisiblePair(null, null), OBSERVED.get());
    }
}
