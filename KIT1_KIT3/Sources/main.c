#include "Cpu.h"
#include "clockMan1.h"
#include "canCom1.h"
#include "dmaController1.h"
#include "csec1.h"
#include "pin_mux.h"
#if CPU_INIT_CONFIG
  #include "Init_Config.h"
#endif
#include <stdint.h>
#include <stdbool.h>

/* Vai trò thiết bị */
//#define MASTER
#define SLAVE

/* CAN ID và Mailbox */
#if defined(MASTER)
    #define TX_MAILBOX_REQ    (0UL)
    #define TX_MSG_ID_REQ     (0x4UL)   // Gửi yêu cầu phanh

    #define RX_MAILBOX_RESP   (1UL)
    #define RX_MSG_ID_RESP    (0x6UL)   // Nhận phản hồi trạng thái phanh
#endif

#if defined(SLAVE)
    #define RX_MAILBOX_REQ    (0UL)
    #define RX_MSG_ID_REQ     (0x4UL)   // Nhận yêu cầu phanh

    #define TX_MAILBOX_SPEED  (1UL)
    #define TX_MSG_ID_SPEED   (0x5UL)   // Gửi tốc độ 2 bánh

    #define TX_MAILBOX_BRAKE  (2UL)
    #define TX_MSG_ID_BRAKE   (0x6UL)   // Phản hồi trạng thái phanh
#endif

/* Biến toàn cục */
uint8_t speed_left = 50;   // rpm giả định
uint8_t speed_right = 52;
uint8_t brake_status[2] = {0x01, 0x02};  // Ví dụ trạng thái phanh

/* Prototype */
void BoardInit(void);
void FlexCANInit(void);
void SendCANData(uint32_t mailbox, uint32_t messageId, uint8_t * data, uint32_t len);

/* Hàm gửi dữ liệu CAN */
void SendCANData(uint32_t mailbox, uint32_t messageId, uint8_t * data, uint32_t len)
{
    flexcan_data_info_t dataInfo =
    {
        .data_length = len,
        .msg_id_type = FLEXCAN_MSG_ID_STD,
        .enable_brs  = false,
        .fd_enable   = false,
        .fd_padding  = 0U
    };

    FLEXCAN_DRV_ConfigTxMb(INST_CANCOM1, mailbox, &dataInfo, messageId);
    FLEXCAN_DRV_Send(INST_CANCOM1, mailbox, &dataInfo, messageId, data);
}

/* Init Clock + Pin */
void BoardInit(void)
{
    CLOCK_SYS_Init(g_clockManConfigsArr, CLOCK_MANAGER_CONFIG_CNT,
                   g_clockManCallbacksArr, CLOCK_MANAGER_CALLBACK_CNT);
    CLOCK_SYS_UpdateConfiguration(0U, CLOCK_MANAGER_POLICY_FORCIBLE);

    PINS_DRV_Init(NUM_OF_CONFIGURED_PINS, g_pin_mux_InitConfigArr);
}

/* Init CAN */
void FlexCANInit(void)
{
    FLEXCAN_DRV_Init(INST_CANCOM1, &canCom1_State, &canCom1_InitConfig0);
}

/* Callback CAN RX */
void CAN_RxCallback(uint8_t instance,
                    flexcan_event_type_t eventType,
                    uint32_t mbIdx,
                    void *userData)
{
    if (eventType == FLEXCAN_EVENT_RX_COMPLETE)
    {
        flexcan_msgbuff_t *rxBuff = (flexcan_msgbuff_t *)userData;

#if defined(SLAVE)
        if (rxBuff->msgId == RX_MSG_ID_REQ) // Master yêu cầu phanh
        {
            SendCANData(TX_MAILBOX_BRAKE, TX_MSG_ID_BRAKE, brake_status, 2);
        }
        /* Đăng ký lại nhận */
        FLEXCAN_DRV_Receive(INST_CANCOM1, RX_MAILBOX_REQ, rxBuff);
#endif

#if defined(MASTER)
        if (rxBuff->msgId == RX_MSG_ID_RESP) // Slave phản hồi phanh
        {
            // Xử lý dữ liệu nếu cần (rxBuff->data[0], rxBuff->data[1])
        }
        FLEXCAN_DRV_Receive(INST_CANCOM1, RX_MAILBOX_RESP, rxBuff);
#endif
    }
}

/* Main */
int main(void)
{
    BoardInit();
    FlexCANInit();

    /* Cấu hình thông tin nhận */
    flexcan_data_info_t dataInfo = {
        .data_length = 2U,
        .msg_id_type = FLEXCAN_MSG_ID_STD,
        .enable_brs  = false,
        .fd_enable   = false,
        .fd_padding  = 0U
    };

#if defined(MASTER)
    static flexcan_msgbuff_t rxBuff;
    FLEXCAN_DRV_ConfigRxMb(INST_CANCOM1, RX_MAILBOX_RESP, &dataInfo, RX_MSG_ID_RESP);
    FLEXCAN_DRV_Receive(INST_CANCOM1, RX_MAILBOX_RESP, &rxBuff);
    FLEXCAN_DRV_InstallEventCallback(INST_CANCOM1, CAN_RxCallback, &rxBuff);

    /* Gửi 1 frame yêu cầu phanh */
    uint8_t reqData[1] = {0x01};
    SendCANData(TX_MAILBOX_REQ, TX_MSG_ID_REQ, reqData, 1);

    while (1)
    {
        // Master chỉ gửi 1 lần, sau đó chờ phản hồi
    }
#endif

#if defined(SLAVE)
    static flexcan_msgbuff_t rxBuff;
    FLEXCAN_DRV_ConfigRxMb(INST_CANCOM1, RX_MAILBOX_REQ, &dataInfo, RX_MSG_ID_REQ);
    FLEXCAN_DRV_Receive(INST_CANCOM1, RX_MAILBOX_REQ, &rxBuff);
    FLEXCAN_DRV_InstallEventCallback(INST_CANCOM1, CAN_RxCallback, &rxBuff);

    while (1)
    {
        // Cứ 2s gửi trạng thái tốc độ bánh
        uint8_t speedData[2] = {speed_left, speed_right};
        SendCANData(TX_MAILBOX_SPEED, TX_MSG_ID_SPEED, speedData, 2);

        SDK_DelayAtLeastUs(2000000, SDK_DEVICE_MAX_FREQUENCY); // 2 giây delay
    }
#endif

    return 0;
}
