#include "Cpu.h"
#include "pin_mux.h"
#include "clockMan1.h"
#include "lpuart1.h"
#include <string.h>
#include <stdbool.h>

#define TIMEOUT_MS         100U
#define BRAKE_LED_PORT     PTD
#define BRAKE_LED_PIN      0U    // PTD0 = LED xanh EVB
#define LED_ACTIVE_LOW     1
#define UART_INSTANCE      INST_LPUART1

#define TX_INTERVAL_MS     500U
#define BRAKE_ON_TIME_MS   5000U

static uint8_t  rxByte;
static char     rxBuffer[32];
static uint8_t  rxIndex = 0;

volatile uint32_t brake_led_timer_ms = 0;
volatile uint32_t ms_counter = 0;

static void set_brake_led(bool on)
{
    if (LED_ACTIVE_LOW) {
        PINS_DRV_WritePin(BRAKE_LED_PORT, BRAKE_LED_PIN, on ? 0 : 1);
    } else {
        PINS_DRV_WritePin(BRAKE_LED_PORT, BRAKE_LED_PIN, on ? 1 : 0);
    }
}

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

        if (strstr(rxBuffer, "BRAKE") != NULL) {
            brake_led_timer_ms = BRAKE_ON_TIME_MS;
            set_brake_led(true);

            const char *ack = "BRAKE_ACK\n";
            LPUART_DRV_SendDataBlocking(UART_INSTANCE,
                                        (const uint8_t *)ack,
                                        strlen(ack),
                                        TIMEOUT_MS);

            rxIndex = 0;
            rxBuffer[0] = '\0';
        }

        LPUART_DRV_SetRxBuffer(UART_INSTANCE, &rxByte, 1U);
    }
}

int main(void)
{
  /* Write your local variable definition here */

  /*** Processor Expert internal initialization. DON'T REMOVE THIS CODE!!! ***/
  #ifdef PEX_RTOS_INIT
    PEX_RTOS_INIT();                   /* Initialization of the selected RTOS. Macro is defined by the RTOS component. */
  #endif
  /*** End of Processor Expert internal initialization.                    ***/
    CLOCK_SYS_Init(g_clockManConfigsArr, CLOCK_MANAGER_CONFIG_CNT,
                   g_clockManCallbacksArr, CLOCK_MANAGER_CALLBACK_CNT);
    CLOCK_SYS_UpdateConfiguration(0U, CLOCK_MANAGER_POLICY_AGREEMENT);

    PINS_DRV_Init(NUM_OF_CONFIGURED_PINS, g_pin_mux_InitConfigArr);

    // Map PTD13 -> LPUART1_RX (ALT3)
    PINS_DRV_SetMuxModeSel(PORTD, 13, PORT_MUX_ALT3);

    LPUART_DRV_Init(UART_INSTANCE, &lpuart1_State, &lpuart1_InitConfig0);

    LPUART_DRV_InstallRxCallback(UART_INSTANCE, rxCallback, NULL);
    LPUART_DRV_ReceiveData(UART_INSTANCE, &rxByte, 1U);

    PINS_DRV_SetPinDirection(BRAKE_LED_PORT, BRAKE_LED_PIN, GPIO_OUTPUT_DIRECTION);
    set_brake_led(false);

    while (1)
    {
        for (volatile uint32_t i = 0; i < 48000; i++);

        ms_counter++;

        if (brake_led_timer_ms > 0) {
            brake_led_timer_ms--;
            if (brake_led_timer_ms == 0) {
                set_brake_led(false);
            }
        }

        if (ms_counter >= TX_INTERVAL_MS) {
            ms_counter = 0;
            const char *msg = "SPEED:2;DOOR:CLOSED;TIRE:OK\n";
            LPUART_DRV_SendDataBlocking(UART_INSTANCE,
                                        (const uint8_t *)msg,
                                        strlen(msg),
                                        TIMEOUT_MS);
        }
    }
}
