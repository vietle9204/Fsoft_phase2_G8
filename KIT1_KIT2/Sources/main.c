
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

/******************************************************************************
 * Definitions
 ******************************************************************************/

/* This example is setup to work by default with EVB. To use it with other boards
   please comment the following line
*/

#define EVB

#ifdef EVB
    #define LED_PORT        PORTD
    #define GPIO_PORT       PTD
    #define PCC_INDEX       PCC_PORTD_INDEX
    #define LED0            15U
    #define LED1            16U
    #define LED2            0U

    #define BTN_GPIO        PTC
    #define BTN1_PIN        13U
    #define BTN2_PIN        12U
    #define BTN_PORT        PORTC
    #define BTN_PORT_IRQn   PORTC_IRQn
#else
    #define LED_PORT        PORTC
    #define GPIO_PORT       PTC
    #define PCC_INDEX       PCC_PORTC_INDEX
    #define LED0            0U
    #define LED1            1U
    #define LED2            2U

    #define BTN_GPIO        PTC
    #define BTN1_PIN        13U
    #define BTN2_PIN        12U
    #define BTN_PORT        PORTC
    #define BTN_PORT_IRQn   PORTC_IRQn
#endif

/* Use this define to specify if the application runs as master or slave */
#define MASTER
/* #define SLAVE */

/* Definition of the TX and RX message buffers depending on the bus role */
#if defined(MASTER)
    #define TX_MAILBOX  (1UL)
    #define TX_MSG_ID   (1UL)
    #define RX_MAILBOX  (0UL)
    #define RX_MSG_ID   (2UL)
#elif defined(SLAVE)
    #define TX_MAILBOX  (0UL)
    #define TX_MSG_ID   (2UL)
    #define RX_MAILBOX  (1UL)
    #define RX_MSG_ID   (1UL)
#endif

typedef enum 
{
    LED0_CHANGE_REQUESTED = 0x00U,
    LED1_CHANGE_REQUESTED = 0x01U
} can_commands_list;
volatile uint8_t speed = 10;
volatile uint8_t wheel = 0; // 0 = bánh trái, 1 = bánh phải
uint8_t speed_slave = 0;
uint8_t wheel_slave = 0;

uint8_t ledRequested = (uint8_t)LED0_CHANGE_REQUESTED;

bool useEncryption = false;

/******************************************************************************
 * Function prototypes
 ******************************************************************************/
void SendCANData(uint32_t mailbox, uint32_t messageId, uint8_t * data, uint32_t len);
void buttonISR(void);
void BoardInit(void);
void GPIOInit(void);
void FlexCANInit(void);

/******************************************************************************
 * Functions
 ******************************************************************************/

/**
 * Button interrupt handler
 */
void buttonISR(void)
{
    /* Check if one of the buttons was pressed */
    uint32_t buttonsPressed = PINS_DRV_GetPortIntFlag(BTN_PORT) &
                                           ((1 << BTN1_PIN) | (1 << BTN2_PIN));
    uint32_t buttons = PINS_DRV_ReadPins(BTN_GPIO);
    bool sendFrame = false;

    if(buttonsPressed != 0)
    {

        /* Set FlexCAN TX value according to the button pressed */
        switch (buttonsPressed)
        {
            case (1 << BTN1_PIN):
				speed += 10;
				if (speed > 100) speed = -100;
				sendFrame = true;
				PINS_DRV_ClearPinIntFlagCmd(BTN_PORT, BTN1_PIN);
				break;
            case (1 << BTN2_PIN):
				wheel ^= 1; // đảo trái/phải
				sendFrame = true;
				PINS_DRV_ClearPinIntFlagCmd(BTN_PORT, BTN2_PIN);
				break; }
        	#if defined(MASTER)
        		if (sendFrame) {
        			uint8_t payload[2];
        			payload[0] = speed;
        			payload[1] = wheel;
        			SendCANData(TX_MAILBOX, TX_MSG_ID, payload, 2U); }
        	#endif }
    }
}
/*
 * @brief: Send data via CAN to the specified mailbox with the specified message id
 * @param mailbox   : Destination mailbox number
 * @param messageId : Message ID
 * @param data      : Pointer to the TX data
 * @param len       : Length of the TX data
 * @return          : None
 */
void SendCANData(uint32_t mailbox, uint32_t messageId, uint8_t * data, uint32_t len)
{
    /* Set information about the data to be sent
     *  - 1 byte in length
     *  - Standard message ID
     *  - Bit rate switch enabled to use a different bitrate for the data segment
     *  - Flexible data rate enabled
     *  - Use zeros for FD padding
     */
    flexcan_data_info_t dataInfo =
    {
            .data_length = len,
            .msg_id_type = FLEXCAN_MSG_ID_STD,
            .enable_brs  = false,
            .fd_enable   = false,
            .fd_padding  = 0U
    };

    /* Configure TX message buffer with index TX_MSG_ID and TX_MAILBOX*/
    FLEXCAN_DRV_ConfigTxMb(INST_CANCOM1, mailbox, &dataInfo, messageId);

    /* Execute send non-blocking */
    FLEXCAN_DRV_Send(INST_CANCOM1, mailbox, &dataInfo, messageId, data);
}

/*
 * @brief : Initialize clocks, pins and power modes
 */
void BoardInit(void)
{

    /* Initialize and configure clocks
     *  -   Setup system clocks, dividers
     *  -   Configure FlexCAN clock, GPIO
     *  -   see clock manager component for more details
     */
    CLOCK_SYS_Init(g_clockManConfigsArr, CLOCK_MANAGER_CONFIG_CNT,
                        g_clockManCallbacksArr, CLOCK_MANAGER_CALLBACK_CNT);
    CLOCK_SYS_UpdateConfiguration(0U, CLOCK_MANAGER_POLICY_FORCIBLE);

    /* Initialize pins
     *  -   Init FlexCAN and GPIO pins
     *  -   See PinSettings component for more info
     */
    PINS_DRV_Init(NUM_OF_CONFIGURED_PINS, g_pin_mux_InitConfigArr);
}

/*
 * @brief Function which configures the LEDs and Buttons
 */
void GPIOInit(void)
{
    /* Output direction for LEDs */
    PINS_DRV_SetPinsDirection(GPIO_PORT, (1 << LED2) | (1 << LED1) | (1 << LED0));

    /* Set Output value LEDs */
    PINS_DRV_ClearPins(GPIO_PORT, 1 << LED1);
    PINS_DRV_SetPins(GPIO_PORT, 1 << LED2);

    /* Setup button pin */
    PINS_DRV_SetPinsDirection(BTN_GPIO, ~((1 << BTN1_PIN)|(1 << BTN2_PIN)));

    /* Setup button pins interrupt */
    PINS_DRV_SetPinIntSel(BTN_PORT, BTN1_PIN, PORT_INT_RISING_EDGE);
    PINS_DRV_SetPinIntSel(BTN_PORT, BTN2_PIN, PORT_INT_RISING_EDGE);

    /* Install buttons ISR */
    INT_SYS_InstallHandler(BTN_PORT_IRQn, &buttonISR, NULL);

    /* Enable buttons interrupt */
    INT_SYS_EnableIRQ(BTN_PORT_IRQn);
}

/*
 * @brief Initialize FlexCAN driver and configure the bit rate
 */
void FlexCANInit(void)
{
    /*
     * Initialize FlexCAN driver
     *  - 8 byte payload size
     *  - FD enabled
     *  - Bus clock as peripheral engine clock
     */
    FLEXCAN_DRV_Init(INST_CANCOM1, &canCom1_State, &canCom1_InitConfig0);
}

volatile int exit_code = 0;
/* User includes (#include below this line is not maintained by Processor Expert) */

/*!
  \brief The main function for the project.
  \details The startup initialization sequence is the following:
 * - __start (startup asm routine)
 * - __init_hardware()
 * - main()
 *   - PE_low_level_init()
 *     - Common_Init()
 *     - Peripherals_Init()
*/
int main(void)
{
  /*** Processor Expert internal initialization. DON'T REMOVE THIS CODE!!! ***/
  #ifdef PEX_RTOS_INIT
    PEX_RTOS_INIT();                 /* Initialization of the selected RTOS. Macro is defined by the RTOS component. */
  #endif
  /*** End of Processor Expert internal initialization.                    ***/

    /* Do the initializations required for this application */
    BoardInit();
    GPIOInit();

    FlexCANInit();

    CSEC_DRV_Init(&csec1_State);

    /* Set information about the data to be received
     *  - 1 byte in length
     *  - Standard message ID
     *  - Bit rate switch enabled to use a different bitrate for the data segment
     *  - Flexible data rate enabled
     *  - Use zeros for FD padding
     */
    flexcan_data_info_t dataInfo =
    {
            .data_length = 2U,
            .msg_id_type = FLEXCAN_MSG_ID_STD,
            .enable_brs  = false,
            .fd_enable   = false,
            .fd_padding  = 0U
    };

    /* Configure RX message buffer with index RX_MSG_ID and RX_MAILBOX */
    FLEXCAN_DRV_ConfigRxMb(INST_CANCOM1, RX_MAILBOX, &dataInfo, RX_MSG_ID);

    while(1) 
    {
    	/* Define receive buffer */
    	flexcan_msgbuff_t recvBuff;
    	/* Start receiving data in RX_MAILBOX. */
    	FLEXCAN_DRV_Receive(INST_CANCOM1, RX_MAILBOX, &recvBuff);
    	/* Wait until the previous FlexCAN receive is completed */
    	while(FLEXCAN_DRV_GetTransferStatus(INST_CANCOM1, RX_MAILBOX) == STATUS_BUSY);
    	#if defined(SLAVE)
    	if (recvBuff.msgId == RX_MSG_ID && recvBuff.dataLen >= 2) {
    		speed_slave = recvBuff.data[0];
    		wheel_slave = recvBuff.data[1];
    	}
		#endif
  #ifdef PEX_RTOS_START
    PEX_RTOS_START();                  /* Startup of the selected RTOS. Macro is defined by the RTOS component. */
  #endif
  /*** End of RTOS startup code.  ***/
  /*** Processor Expert end of main routine. DON'T MODIFY THIS CODE!!! ***/
  for(;;) {
    if(exit_code != 0) {
      break;
    }
  }
  return exit_code;
  /*** Processor Expert end of main routine. DON'T WRITE CODE BELOW!!! ***/
} /*** End of main routine. DO NOT MODIFY THIS TEXT!!! ***/

/* END main */
/*!
** @}
*/
/*
** ###################################################################
**
**     This file was created by Processor Expert 10.1 [05.21]
**     for the Freescale S32K series of microcontrollers.
**
** ###################################################################
*/
