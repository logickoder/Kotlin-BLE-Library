/*
 * Copyright (c) 2022, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.kotlin.ble.mock

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.kotlin.ble.client.api.BleGatt
import no.nordicsemi.android.kotlin.ble.client.api.OnCharacteristicChanged
import no.nordicsemi.android.kotlin.ble.client.api.OnCharacteristicRead
import no.nordicsemi.android.kotlin.ble.client.api.OnCharacteristicWrite
import no.nordicsemi.android.kotlin.ble.client.api.OnConnectionStateChanged
import no.nordicsemi.android.kotlin.ble.client.api.OnDescriptorRead
import no.nordicsemi.android.kotlin.ble.client.api.OnDescriptorWrite
import no.nordicsemi.android.kotlin.ble.client.api.OnMtuChanged
import no.nordicsemi.android.kotlin.ble.client.api.OnPhyRead
import no.nordicsemi.android.kotlin.ble.client.api.OnPhyUpdate
import no.nordicsemi.android.kotlin.ble.client.api.OnReadRemoteRssi
import no.nordicsemi.android.kotlin.ble.client.api.OnReliableWriteCompleted
import no.nordicsemi.android.kotlin.ble.client.api.OnServicesDiscovered
import no.nordicsemi.android.kotlin.ble.core.ClientDevice
import no.nordicsemi.android.kotlin.ble.core.MockClientDevice
import no.nordicsemi.android.kotlin.ble.core.MockServerDevice
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectOptions
import no.nordicsemi.android.kotlin.ble.core.data.BleGattConnectionStatus
import no.nordicsemi.android.kotlin.ble.core.data.BleGattOperationStatus
import no.nordicsemi.android.kotlin.ble.core.data.BleGattPhy
import no.nordicsemi.android.kotlin.ble.core.data.BleWriteType
import no.nordicsemi.android.kotlin.ble.core.data.GattConnectionState
import no.nordicsemi.android.kotlin.ble.core.data.PhyOption
import no.nordicsemi.android.kotlin.ble.server.api.OnCharacteristicReadRequest
import no.nordicsemi.android.kotlin.ble.server.api.OnCharacteristicWriteRequest
import no.nordicsemi.android.kotlin.ble.server.api.OnClientConnectionStateChanged
import no.nordicsemi.android.kotlin.ble.server.api.OnDescriptorReadRequest
import no.nordicsemi.android.kotlin.ble.server.api.OnDescriptorWriteRequest
import no.nordicsemi.android.kotlin.ble.server.api.OnServerPhyRead
import no.nordicsemi.android.kotlin.ble.server.api.OnServerPhyUpdate
import no.nordicsemi.android.kotlin.ble.server.api.OnServiceAdded
import no.nordicsemi.android.kotlin.ble.server.api.ServerAPI

object MockEngine {
    //TODO allow for many devices
    private val _advertisedServers = MutableStateFlow(emptyList<MockServerDevice>())
    internal val advertisedServers = _advertisedServers.asStateFlow()

    private val registeredServers = mutableMapOf<MockServerDevice, ServerAPI>()
    private val registeredClients = mutableMapOf<MockClientDevice, BleGatt>()
    private val connections = mutableMapOf<MockServerDevice, MockClientDevice>()
    val reversedConnections
        get() = connections.entries.associate{(k,v)-> v to k}

    private var registeredServices = emptyList<BluetoothGattService>()
    private val enabledNotifications = mutableMapOf<BluetoothGattCharacteristic, Boolean>()

    private val connectionParams = mutableMapOf<Pair<ServerDevice, ClientDevice>, ConnectionParams>()

    private var requests = MockRequestHolder()

    fun addServices(services: List<BluetoothGattService>) {
        registeredServices = services
    }

    fun registerServer(server: ServerAPI) {
        val device = MockServerDevice()
        registeredServers[device] = server
        registeredServices.forEach {
            server.onEvent(OnServiceAdded(it, BleGattOperationStatus.GATT_SUCCESS))
        }
        advertiseServer(device)
    }

    fun connectToServer(device: MockServerDevice, client: BleGatt, options: BleGattConnectOptions) {
        val server = registeredServers[device]!!
        val clientDevice = MockClientDevice()
        connections[device] = clientDevice
        connectionParams[device to clientDevice] = ConnectionParams(txPhy = options.phy, rxPhy = options.phy)
        registeredClients[clientDevice] = client
        server.onEvent(OnClientConnectionStateChanged(clientDevice, BleGattConnectionStatus.SUCCESS, GattConnectionState.STATE_CONNECTED))
    }

    private fun advertiseServer(device: MockServerDevice) {
        _advertisedServers.value = _advertisedServers.value + device
    }

    //Server side

    fun sendResponse(device: ClientDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        val gattStatus = BleGattOperationStatus.create(status)
        val client = registeredClients[device]

        if (value == null) {
            client?.onEvent(OnReliableWriteCompleted(BleGattOperationStatus.GATT_SUCCESS))
            return
        }

        val event = when (val request = requests.getRequest(requestId)) {
            is MockCharacteristicRead -> OnCharacteristicRead(request.characteristic, value, gattStatus)
            is MockCharacteristicWrite -> OnCharacteristicWrite(request.characteristic, gattStatus)
            is MockDescriptorRead -> OnDescriptorRead(request.descriptor, value, gattStatus)
            is MockDescriptorWrite -> OnDescriptorWrite(request.descriptor, gattStatus)
        }
        client?.onEvent(event)
    }

    fun notifyCharacteristicChanged(
        device: ClientDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean,
        value: ByteArray
    ) {
        if (enabledNotifications[characteristic] == true) {
            registeredClients[device]?.onEvent(OnCharacteristicChanged(characteristic, value))
        }
    }

    fun connect(device: ClientDevice, autoConnect: Boolean) {
        registeredClients[device]?.onEvent(OnConnectionStateChanged(BleGattConnectionStatus.SUCCESS, GattConnectionState.STATE_CONNECTED))
    }

    fun readPhy(device: ClientDevice) {
        val server = reversedConnections[device]!!
        val params = connectionParams[server to device]!!
        registeredServers[server]?.onEvent(OnServerPhyRead(device, params.txPhy!!, params.rxPhy!!, BleGattOperationStatus.GATT_SUCCESS))
    }

    fun requestPhy(device: ClientDevice, txPhy: BleGattPhy, rxPhy: BleGattPhy, phyOption: PhyOption) {
        val server = reversedConnections[device]!!
        val params = connectionParams[server to device]!!
        connectionParams[server to device] = params.copy(txPhy = txPhy, rxPhy = rxPhy, phyOption = phyOption)

        registeredServers[server]?.onEvent(OnServerPhyUpdate(device, txPhy, rxPhy, BleGattOperationStatus.GATT_SUCCESS))
    }

    //Client side

    fun writeCharacteristic(
        device: MockServerDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: BleWriteType
    ) {
        val clientDevice = connections[device]!!
        val isResponseNeeded = when (writeType) {
            BleWriteType.NO_RESPONSE -> false
            BleWriteType.DEFAULT,
            BleWriteType.SIGNED -> true
        }
        val request = requests.newWriteRequest(characteristic)
        registeredServers[device]?.onEvent(OnCharacteristicWriteRequest(clientDevice, request.requestId, characteristic, false, isResponseNeeded, 0, value))
    }

    fun readCharacteristic(device: MockServerDevice, characteristic: BluetoothGattCharacteristic) {
        val clientDevice = connections[device]!!
        val request = requests.newReadRequest(characteristic)
        registeredServers[device]?.onEvent(OnCharacteristicReadRequest(clientDevice, request.requestId, 0, characteristic))
    }

    fun enableCharacteristicNotification(device: MockServerDevice, characteristic: BluetoothGattCharacteristic) {
        enabledNotifications[characteristic] = true
    }

    fun disableCharacteristicNotification(device: MockServerDevice, characteristic: BluetoothGattCharacteristic) {
        enabledNotifications[characteristic] = false
    }

    fun writeDescriptor(device: MockServerDevice, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        val clientDevice = connections[device]!!
        val request = requests.newWriteRequest(descriptor)
        registeredServers[device]?.onEvent(OnDescriptorWriteRequest(clientDevice, request.requestId, descriptor, false, true, 0, value))
    }

    fun readDescriptor(device: MockServerDevice, descriptor: BluetoothGattDescriptor) {
        val clientDevice = connections[device]!!
        val request = requests.newReadRequest(descriptor)
        registeredServers[device]?.onEvent(OnDescriptorReadRequest(clientDevice, request.requestId, 0, descriptor))
    }

    fun readRemoteRssi(device: MockServerDevice) {
        val clientDevice = connections[device]!!
        val params = connectionParams[device to clientDevice]!!
        registeredClients[clientDevice]?.onEvent(OnReadRemoteRssi(params.rssi ?: 50, BleGattOperationStatus.GATT_SUCCESS))
    }

    fun readPhy(device: MockServerDevice) {
        val client = connections[device]!!
        val params = connectionParams[device to client]!!
        registeredClients[client]?.onEvent(OnPhyRead(params.txPhy!!, params.rxPhy!!, BleGattOperationStatus.GATT_SUCCESS))
    }

    fun discoverServices(device: MockServerDevice) {
        val clientDevice = connections[device]!!
        registeredClients[clientDevice]?.onEvent(OnServicesDiscovered(registeredServices, BleGattOperationStatus.GATT_SUCCESS))
    }

    fun setPreferredPhy(device: MockServerDevice, txPhy: BleGattPhy, rxPhy: BleGattPhy, phyOption: PhyOption) {
        val clientDevice = connections[device]!!
        val params = connectionParams[device to clientDevice]!!
        connectionParams[device to clientDevice] = params.copy(txPhy = txPhy, rxPhy = rxPhy, phyOption = phyOption)

        registeredClients[clientDevice]?.onEvent(OnPhyUpdate(txPhy, rxPhy, BleGattOperationStatus.GATT_SUCCESS))
    }

    fun requestMtu(device: MockServerDevice, mtu: Int) {
        val clientDevice = connections[device]!!
        val params = connectionParams[device to clientDevice]!!
        connectionParams[device to clientDevice] = params.copy(mtu = mtu)

        registeredClients[clientDevice]?.onEvent(OnMtuChanged(mtu, BleGattOperationStatus.GATT_SUCCESS))
    }
}
