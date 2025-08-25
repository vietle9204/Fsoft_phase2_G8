#include "Cpu.h"
#include "pin_mux.h"
#include "clockMan1.h"
#include "lpuart1.h"
#include <string.h>
#include <stdbool.h>

#define TIMEOUT_MS        100U
#define UART_INSTANCE     INST_LPUART1

#define TX_INTERVAL_MS    500U     // chu kỳ gửi msgList
#define LED_ON_TIME_MS    500U

// LED pin mapping trên EVB S32K144
#define LED_BLUE_PORT     PTD
#define LED_BLUE_PIN      0U     // PTD0 = LED xanh dương
#define LED_GREEN_PORT    PTD
#define LED_GREEN_PIN     16U    // PTD16 = LED xanh lá
#define LED_RED_PORT      PTD
#define LED_RED_PIN       15U    // PTD15 = LED đỏ
#define LED_ACTIVE_LOW    1

// UART buffer
static uint8_t  rxByte;
static char     rxBuffer[64];
static uint8_t  rxIndex = 0;

// timer LED
volatile uint32_t blue_timer = 0;
volatile uint32_t green_timer = 0;
volatile uint32_t red_timer = 0;
volatile uint32_t ms_counter = 0;

// Flags để xử lý trong main loop
volatile bool sendBlueAck = false;
volatile bool sendGreenAck = false;
volatile bool sendRedAck = false;

// Chuỗi gửi định kỳ
const char *msgList[] = {
    "SPEED:1.5;2.0\n",
    "BRAKE:OK;ERROR\n",
    "DISTANCE:FAR;LIGHT:NORMAL;HUMIDITY:NORMAL;DOOR:OPENED\n",
    "LIGHT:ON;AC:ON;WIPER:ON\n"
};
#define MSG_COUNT   (sizeof(msgList) / sizeof(msgList[0]))
static uint8_t currentMsgIndex = 0;

// Hàm bật/tắt LED
static void set_led(GPIO_Type *port, uint32_t pin, bool on)
{
    if (LED_ACTIVE_LOW) {
        PINS_DRV_WritePin(port, pin, on ? 0 : 1);
    } else {
        PINS_DRV_WritePin(port, pin, on ? 1 : 0);
    }
}

// Callback UART
void rxCallback(void *driverState, uart_event_t event, void *userData)
{
    (void)driverState;
    (void)userData;

    if (event == UART_EVENT_RX_FULL)
    {
        if (rxIndex < sizeof(rxBuffer) - 1) {
            rxBuffer[rxIndex++] = (char)rxByte;
            rxBuffer[rxIndex] = '\0';
        } else {
            rxIndex = 0;
        }

        // Kiểm tra chuỗi nhận được
        if (strstr(rxBuffer, "BRAKE LEFT") != NULL) {
            blue_timer = LED_ON_TIME_MS;
            set_led(LED_BLUE_PORT, LED_BLUE_PIN, true);
            sendBlueAck = true;
            rxIndex = 0; rxBuffer[0] = '\0';
        }
        else if (strstr(rxBuffer, "BRAKE RIGHT") != NULL) {
            green_timer = LED_ON_TIME_MS;
            set_led(LED_GREEN_PORT, LED_GREEN_PIN, true);
            sendGreenAck = true;
            rxIndex = 0; rxBuffer[0] = '\0';
        }
        else if (strstr(rxBuffer, "STOP") != NULL) {
            red_timer = LED_ON_TIME_MS;
            set_led(LED_RED_PORT, LED_RED_PIN, true);
            sendRedAck = true;
            rxIndex = 0; rxBuffer[0] = '\0';
        }

        // nhận tiếp byte sau
        LPUART_DRV_SetRxBuffer(UART_INSTANCE, &rxByte, 1U);
    }
}

int main(void)
{
    CLOCK_SYS_Init(g_clockManConfigsArr, CLOCK_MANAGER_CONFIG_CNT,
                   g_clockManCallbacksArr, CLOCK_MANAGER_CALLBACK_CNT);
    CLOCK_SYS_UpdateConfiguration(0U, CLOCK_MANAGER_POLICY_AGREEMENT);

    PINS_DRV_Init(NUM_OF_CONFIGURED_PINS, g_pin_mux_InitConfigArr);

    // Map UART: PTC6 -> TX (ALT4), PTD14 -> RX (ALT3)
    PINS_DRV_SetMuxModeSel(PORTC, 6, PORT_MUX_ALT4);
    PINS_DRV_SetMuxModeSel(PORTD, 14, PORT_MUX_ALT3);

    LPUART_DRV_Init(UART_INSTANCE, &lpuart1_State, &lpuart1_InitConfig0);

    LPUART_DRV_InstallRxCallback(UART_INSTANCE, rxCallback, NULL);
    LPUART_DRV_ReceiveData(UART_INSTANCE, &rxByte, 1U);

    // Init LED
    PINS_DRV_SetPinDirection(LED_BLUE_PORT, LED_BLUE_PIN, GPIO_OUTPUT_DIRECTION);
    PINS_DRV_SetPinDirection(LED_GREEN_PORT, LED_GREEN_PIN, GPIO_OUTPUT_DIRECTION);
    PINS_DRV_SetPinDirection(LED_RED_PORT, LED_RED_PIN, GPIO_OUTPUT_DIRECTION);

    set_led(LED_BLUE_PORT, LED_BLUE_PIN, false);
    set_led(LED_GREEN_PORT, LED_GREEN_PIN, false);
    set_led(LED_RED_PORT, LED_RED_PIN, false);

    while (1)
    {
        // delay ~1ms
        for (volatile uint32_t i = 0; i < 48000; i++);

        ms_counter++;

        // Timeout LED
        if (blue_timer > 0 && --blue_timer == 0) set_led(LED_BLUE_PORT, LED_BLUE_PIN, false);
        if (green_timer > 0 && --green_timer == 0) set_led(LED_GREEN_PORT, LED_GREEN_PIN, false);
        if (red_timer > 0 && --red_timer == 0) set_led(LED_RED_PORT, LED_RED_PIN, false);

        // Gửi ACK trong main loop
        if (sendBlueAck) {
            const char *ack = "BRAKE_LEFT_ACK\n";
            LPUART_DRV_SendDataBlocking(UART_INSTANCE, (const uint8_t *)ack, strlen(ack), TIMEOUT_MS);
            sendBlueAck = false;
        }
        if (sendGreenAck) {
            const char *ack = "BRAKE_RIGHT_ACK\n";
            LPUART_DRV_SendDataBlocking(UART_INSTANCE, (const uint8_t *)ack, strlen(ack), TIMEOUT_MS);
            sendGreenAck = false;
        }
        if (sendRedAck) {
            const char *ack = "STOP_ACK\n";
            LPUART_DRV_SendDataBlocking(UART_INSTANCE, (const uint8_t *)ack, strlen(ack), TIMEOUT_MS);
            sendRedAck = false;
        }

        // Chu kỳ gửi chuỗi định kỳ
        if (ms_counter >= TX_INTERVAL_MS) {
            ms_counter = 0;

            const char *msg = msgList[currentMsgIndex];
            LPUART_DRV_SendDataBlocking(UART_INSTANCE,
                                        (const uint8_t *)msg,
                                        strlen(msg),
                                        TIMEOUT_MS);

            currentMsgIndex++;
            if (currentMsgIndex >= MSG_COUNT) currentMsgIndex = 0;
        }
    }
}
