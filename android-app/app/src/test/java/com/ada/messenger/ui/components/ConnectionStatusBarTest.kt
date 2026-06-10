package com.ada.messenger.ui.components

import com.ada.messenger.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStatusBarTest {

    @Test
    fun `connectionRouteLabelRes distinguishes supported transport routes`() {
        assertEquals(R.string.conn_route_iroh_live, connectionRouteLabelRes("iroh_live"))
        assertEquals(R.string.conn_route_bridge, connectionRouteLabelRes("bridge_websocket_tls"))
        assertEquals(R.string.conn_route_bridge, connectionRouteLabelRes("bridge_domain_front"))
        assertEquals(R.string.conn_route_bridge, connectionRouteLabelRes("bridge_meek"))
        assertEquals(R.string.conn_route_bridge, connectionRouteLabelRes("bridge_obfs4"))
        assertEquals(R.string.conn_route_local_mesh, connectionRouteLabelRes("local_mesh"))
        assertEquals(R.string.conn_route_mailbox, connectionRouteLabelRes("mailbox_bridge"))
        assertEquals(R.string.conn_route_offline_queue, connectionRouteLabelRes("offline_queue"))
        assertEquals(R.string.conn_route_relay_only_deferred, connectionRouteLabelRes("relay_only_deferred"))
        assertEquals(R.string.conn_route_failed, connectionRouteLabelRes("failed"))
    }

    @Test
    fun `connectionRouteLabelRes ignores unknown routes`() {
        assertNull(connectionRouteLabelRes(null))
        assertNull(connectionRouteLabelRes("unexpected_route"))
    }
}