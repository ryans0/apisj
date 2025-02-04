pragma solidity ^0.4.24;

contract Owners {

    //@dev These events occur when the owner change agenda is registered / confirmed / revoked / executed.
    event OwnerChangeSubmission(uint indexed ownerChangeId, address indexed owner, string message);
    event OwnerChangeConfirmation(address indexed owner, uint indexed changeId);
    event OwnerChangeRevocation(address indexed owner, uint indexed changeId);
    event OwnerAddition(address indexed owner);
    event OwnerRemoval(address indexed owner);

    //@dev These events occur when the requirement change agenda is registered / confirmed / revoked / executed.
    event RequirementChangeSubmission(uint indexed requiredChangeId, uint require);
    event RequirementChangeConfirmation(address indexed owner, uint indexed changeId);
    event RequirementChangeRevocation(address indexed owner, uint indexed changeId);
    event RequirementChangeExecution(uint changeId);

    uint16 constant public MAX_OWNER_COUNT = 50;


    mapping(uint32 => OwnerChange) public ownerChanges;
    mapping(uint32 => RequirementChange) public requirementChanges;

    mapping(uint32 => mapping (address => bool)) public ownerChangeConfirmations;
    mapping(uint32 => mapping (address => bool)) public requirementChangeConfirmations;

    mapping(address => bool) public isOwner;
    address[] public owners;
    uint16 public required;

    uint32 public requirementChangeCount;
    uint32 public ownerChangeCount;


    struct OwnerChange {
        address owner;
        bool isAdd;
        bool executed;
    }

    struct RequirementChange {
        uint16 requirement;
        bool executed;
    }




    /**
     * @dev The called address must be in the owner list.
     */
    modifier ownerExists(address _owner) {
        require(isOwner[_owner]);
        _;
    }

    /**
     * @dev The called address must not be in the owner list.
     */
    modifier ownerDoesNotExist(address owner) {
        require(!isOwner[owner]);
        _;
    }

    modifier ownerEnoughToRemove() {
        require(owners.length > 1);
        _;
    }

    modifier ownerNotEnoughToAdd() {
        require(owners.length < MAX_OWNER_COUNT);
        _;
    }

    /**
     * @dev The address should not be empty.
     */
    modifier notNull(address _address) {
        require(_address != 0x0);
        _;
    }

    /**
     * @dev The minimum quorum should be the correct value.
     */
    modifier validRequirement (uint256 _ownerCount, uint16 _required) {
        require(_ownerCount <= MAX_OWNER_COUNT
        && _required <= _ownerCount
        && _required != 0
        && _ownerCount != 0);
        _;
    }


    /**
     * @dev "_owner" should confirm the "_changeId" agenda.
     */
    modifier confirmedOwnerChange(uint32 _changeId, address _owner) {
        require(ownerChangeConfirmations[_changeId][_owner]);
        _;
    }

    /**
     * @dev "_owner" should not have confirmed the "_changeId" agenda.
     */
    modifier notConfirmedOwnerChange(uint32 _changeId, address _owner) {
        require(!ownerChangeConfirmations[_changeId][_owner]);
        _;
    }

    /**
     * @dev The "_changeId" item should not have been executed.
     */
    modifier notExecutedOwnerChange(uint32 _changeId) {
        require(!ownerChanges[_changeId].executed);
        _;
    }



    /**
     * @dev "_owner" should confirm the "_changeId" agenda.
     */
    modifier confirmedRequirement(uint32 _changeId, address _owner) {
        require(requirementChangeConfirmations[_changeId][_owner]);
        _;
    }

    /**
     * @dev "_owner" should not have confirmed the "_changeId" agenda.
     */
    modifier notConfirmedRequirement(uint32 _changeId, address _owner) {
        require(!requirementChangeConfirmations[_changeId][_owner]);
        _;
    }

    /**
     * @dev The "_changeId" item should not have been executed.
     */
    modifier notExecutedRequirement(uint32 _changeId) {
        require(!requirementChanges[_changeId].executed);
        _;
    }







    //------------------------------------------------------------
    // MultiSig : Owner Add/Remove process
    //------------------------------------------------------------
    /**
     * @dev Register an agenda to add "_owner"
     *      The owner who registers the item will automatically approve the item.
     *      If the minimum quorum(required) is 1, the item is executed at the same time as the item is registered.
     * @return ownerChangeId ID of the agenda
     */
    function registerOwnerAdd(address _owner)
    public
    notNull(_owner)
    ownerExists(msg.sender)
    ownerDoesNotExist(_owner)
    ownerNotEnoughToAdd()
    returns (uint ownerChangeId)
    {
        return registerChangeOwner(_owner, true);
    }

    /**
     * @dev Register an agenda that removes "_owner" from the owner list.
     */
    function registerOwnerRemove(address _owner)
    public
    notNull(_owner)
    ownerExists(msg.sender)
    ownerExists(_owner)
    ownerEnoughToRemove()
    returns (uint ownerChangeId)
    {
        return registerChangeOwner(_owner, false);
    }


    function registerChangeOwner(address _owner, bool _isAdd)
    internal
    ownerExists(msg.sender)
    returns (uint32 ownerChangeId)
    {
        ownerChangeId = ownerChangeCount;

        ownerChanges[ownerChangeId] = OwnerChange({
            owner : _owner,
            isAdd : _isAdd,
            executed : false
            });

        ownerChangeCount += 1;
        if(_isAdd) {
            emit OwnerChangeSubmission(ownerChangeId, _owner, "Add");
        } else {
            emit OwnerChangeSubmission(ownerChangeId, _owner, "Remove");
        }

        confirmOwnerChange(ownerChangeId);
    }


    function confirmOwnerChange(uint32 _changeId)
    public
    ownerExists(msg.sender)
    notExecutedOwnerChange(_changeId)
    notConfirmedOwnerChange(_changeId, msg.sender)
    {
        ownerChangeConfirmations[_changeId][msg.sender] = true;
        emit OwnerChangeConfirmation(msg.sender, _changeId);

        executeOwnerChange(_changeId);
    }

    function revokeOwnerChangeConfirmation(uint32 _changeId)
    public
    ownerExists(msg.sender)
    notExecutedOwnerChange(_changeId)
    confirmedOwnerChange(_changeId, msg.sender)
    {
        ownerChangeConfirmations[_changeId][msg.sender] = false;
        emit OwnerChangeRevocation(msg.sender, _changeId);
    }

    function executeOwnerChange(uint32 _changeId)
    internal
    ownerExists(msg.sender)
    notExecutedOwnerChange(_changeId)
    confirmedOwnerChange(_changeId, msg.sender)
    {
        if(isOwnerChangeConfirmed(_changeId)) {
            OwnerChange storage ownerChange = ownerChanges[_changeId];

            if(ownerChange.isAdd) {
                addOwner(ownerChange.owner);
            }
            else {
                removeOwner(ownerChange.owner);
            }

            ownerChange.executed = true;
        }
    }


    function isOwnerChangeConfirmed(uint32 _changeId)
    internal
    constant
    returns (bool)
    {
        uint count = 0;
        for(uint i = 0; i < owners.length; i++) {
            if(ownerChangeConfirmations[_changeId][owners[i]])
                count += 1;
            if(count == required)
                return true;
        }
    }


    function addOwner(address _owner)
    internal
    ownerDoesNotExist(_owner)
    ownerNotEnoughToAdd()
    {
        isOwner[_owner] = true;
        owners.push(_owner);

        emit OwnerAddition(_owner);
    }


    function removeOwner(address _owner)
    internal
    ownerExists(_owner)
    ownerEnoughToRemove()
    {
        isOwner[_owner] = false;

        for(uint i =0 ; i < owners.length; i++) {
            if(owners[i] == _owner) {
                owners[i] = owners[owners.length - 1];
                break;
            }
        }

        owners.length -= 1;

        if(owners.length < required) {
            required = uint16(owners.length);
        }

        emit OwnerRemoval(_owner);
    }





    //------------------------------------------------------------
    // MultiSig : Requirement change process
    //------------------------------------------------------------
    function registerRequirementChange(uint16 _requirement)
    public
    ownerExists(msg.sender)
    validRequirement(owners.length, _requirement)
    returns (uint32 requirementChangeId)
    {
        requirementChangeId = requirementChangeCount;
        requirementChanges[requirementChangeId] = RequirementChange({
            requirement : _requirement,
            executed : false
            });

        requirementChangeCount += 1;
        emit RequirementChangeSubmission(requirementChangeId, _requirement);

        confirmRequirementChange(requirementChangeId);
    }


    function confirmRequirementChange(uint32 _changeId)
    public
    ownerExists(msg.sender)
    notExecutedRequirement(_changeId)
    notConfirmedRequirement(_changeId, msg.sender)
    validRequirement(owners.length, requirementChanges[_changeId].requirement)
    {
        requirementChangeConfirmations[_changeId][msg.sender] = true;
        emit RequirementChangeConfirmation(msg.sender, _changeId);

        executeRequirementChange(_changeId);
    }

    function revokeRequirementChangeConfirmation(uint32 _changeId)
    public
    ownerExists(msg.sender)
    notExecutedRequirement(_changeId)
    confirmedRequirement(_changeId, msg.sender)
    {
        requirementChangeConfirmations[_changeId][msg.sender] = false;
        emit RequirementChangeRevocation(msg.sender, _changeId);
    }


    function executeRequirementChange(uint32 _changeId)
    internal
    ownerExists(msg.sender)
    notExecutedRequirement(_changeId)
    confirmedRequirement(_changeId, msg.sender)
    validRequirement(owners.length, requirementChanges[_changeId].requirement)
    {
        if(isRequirementChangeConfirmed(_changeId)) {
            RequirementChange storage requirementChange = requirementChanges[_changeId];

            required = requirementChange.requirement;
            requirementChange.executed = true;

            emit RequirementChangeExecution(_changeId);
        }
    }


    function isRequirementChangeConfirmed(uint32 _changeId)
    internal
    constant
    returns (bool)
    {
        uint count = 0;
        for(uint24 i = 0; i < owners.length; i++) {
            if(requirementChangeConfirmations[_changeId][owners[i]])
                count += 1;
            if(count == required)
                return true;
        }
    }
}



/**
 * @title SafeMath
 * @dev Math operations with safety checks that throw on error
 */
library SafeMath {
    /**
    * @dev Multiplies two numbers, reverts on overflow.
    */
    function mul(uint256 a, uint256 b) internal pure returns (uint256) {
        // Gas optimization: this is cheaper than requiring 'a' not being zero, but the
        // benefit is lost if 'b' is also tested.
        // See: https://github.com/OpenZeppelin/openzeppelin-solidity/pull/522
        if (a == 0) {
            return 0;
        }

        uint256 c = a * b;
        require(c / a == b);

        return c;
    }

    /**
    * @dev Integer division of two numbers truncating the quotient, reverts on division by zero.
    */
    function div(uint256 a, uint256 b) internal pure returns (uint256) {
        require(b > 0); // Solidity only automatically asserts when dividing by 0
        uint256 c = a / b;
        // assert(a == b * c + a % b); // There is no case in which this doesn't hold

        return c;
    }

    /**
    * @dev Subtracts two numbers, reverts on overflow (i.e. if subtrahend is greater than minuend).
    */
    function sub(uint256 a, uint256 b) internal pure returns (uint256) {
        require(b <= a);
        uint256 c = a - b;

        return c;
    }

    /**
    * @dev Adds two numbers, reverts on overflow.
    */
    function add(uint256 a, uint256 b) internal pure returns (uint256) {
        uint256 c = a + b;
        require(c >= a);

        return c;
    }

    /**
    * @dev Divides two numbers and returns the remainder (unsigned integer modulo),
    * reverts when dividing by zero.
    */
    function mod(uint256 a, uint256 b) internal pure returns (uint256) {
        require(b != 0);
        return a % b;
    }
}


contract EarlyBirdManager is Owners {
    using SafeMath for uint256;


    event EarlyBirdRegister(address participant, address masternode, address recipient, uint256 collateral);
    event MasternodeCancel (address participant, address masternode, uint256 collateral);


    /**
     * @dev 하루 동안 생성되는 블록의 수
     */
    uint256 constant private BLOCKS_PER_DAY = 10800;


    /**
     * @dev 마스터노드가 시작되고 종료될 때까지 유지되는 블록 수
     */
    //uint256 constant private PERIOD_MASTERNODE = BLOCKS_PER_DAY*90;
    uint256 constant private PERIOD_MASTERNODE = 972000;



    uint256 constant private COLLATERAL_GENERAL   =  50000*(10**18);
    uint256 constant private COLLATERAL_MAJOR     = 200000*(10**18);
    uint256 constant private COLLATERAL_PRIVATE   = 500000*(10**18);


    address public foundationAccount;

    uint256 constant public CAP_GENERAL = 4000;
    uint256 constant public CAP_MAJOR = 3000;
    uint256 constant public CAP_PRIVATE = 2000;


    mapping(uint256 => address[]) public mnListGeneral;
    mapping(uint256 => address[]) public mnListMajor;
    mapping(uint256 => address[]) public mnListPrivate;

    mapping(address => uint32) public participationNonce;
    mapping(address => Participation) masternodeInfo;

    address private _worker;
    address private _platform;


    struct Participation {
        address participant;
        uint32 round;
        uint32 nonce;
        uint16 index;
        uint8 canceled;
        uint256 collateral;
    }



    modifier emptyOwner () {
        require(owners.length == 0 && required == 0);
        _;
    }


    /**
     * @dev 현재 블록 번호에서 각 마스터노드에 참여 가능한 자리가 있는지 확인한다.
     * @param collateral 참여 가능한지 확인하려는 마스터노드의 담보금액
     */
    modifier enoughMasternodeSpace (uint256 collateral) {
        if(collateral == COLLATERAL_GENERAL) {
            require(mnListGeneral[getRound()].length < CAP_GENERAL);
        } else if(collateral == COLLATERAL_MAJOR) {
            require(mnListMajor[getRound()].length < CAP_MAJOR);
        } else if(collateral == COLLATERAL_PRIVATE) {
            require(mnListPrivate[getRound()].length < CAP_PRIVATE);
        } else {
            revert();
        }

        _;
    }

    /**
     * @dev 현재 블록 번호가 얼리버드 참여 가능한 번호인지 확인한다.
     */
    modifier registrableBlock () {
        require(block.number.mod(PERIOD_MASTERNODE) < BLOCKS_PER_DAY);
        _;
    }


    /**
     * @dev 얼리버드 신청했을 때, 담보금액이 컨트렉트에 충분한지 확인한다.
     */
    modifier validCollateral(uint256 collateral) {
        require(getTotalCollateral(getRound()).add(collateral) <= address(this).balance);
        _;
    }


    modifier onlyWorker() {
        require(msg.sender == _worker);
        _;
    }

    modifier enoughBalance(uint256 amount) {
        require(address(this).balance.sub(amount) >= getTotalCollateral(getRound()));
        _;
    }

    modifier masternodeExist(address masternode) {
        require(masternodeInfo[masternode].participant != 0x0);
        _;
    }

    modifier cancelableMasternode (address masternode) {
        require (masternodeInfo[masternode].canceled == 0);
        _;
    }

    modifier cancelableBlock(address masternode) {
        uint256 ebStart;
        uint256 mnEnd;

        (ebStart, , mnEnd) = getPeriodOfRound(masternodeInfo[masternode].round);

        require(block.number >= ebStart && block.number < mnEnd);
        _;
    }

    modifier platformExist() {
        require(_platform != 0x0);
        _;
    }





    function getWorker()
    public
    view
    returns (address) {
        return _worker;
    }

    function getPlatform()
    public
    view
    returns (address) {
        return _platform;
    }


    /**
     * @dev
     *  /**
     @dev *
    /현재 블록 번호에 해당하는 라운드에 담보된 자산 합계을 구한다.
     */
    function getTotalCollateral (uint32 round)
    public
    view
    returns (uint256 totalCollateral)
    {
        totalCollateral += (mnListGeneral[round].length.mul(COLLATERAL_GENERAL));
        totalCollateral += (mnListMajor[round].length.mul(COLLATERAL_MAJOR));
        totalCollateral += (mnListPrivate[round].length.mul(COLLATERAL_PRIVATE));
    }


    /**
     * @dev 해당하는 마스터노드가 현재 블록에서 몇 번째 라운드인지 확인한다.
     *      첫번째 라운드는 1
     *
     * @return round >= 1
     */
    function getRound()
    public
    view
    returns (uint32)
    {
        return uint32(block.number/PERIOD_MASTERNODE + 1);
    }


    function getBalance()
    public
    view
    returns (uint256 balance) {
        balance = address(this).balance;
    }


    function isAppliableEarlyBird()
    public
    view
    returns (bool)
    {
        if(block.number.mod(PERIOD_MASTERNODE) >= BLOCKS_PER_DAY) {
            return false;
        }

        return true;
    }


    /**
     * @dev 얼리버드로 참여한 마스터노드의 참여 정보를 확인한다.
     *      마스터노드 주소는 earlyBirdRegister 메서드 실행 후 발생하는 EarlyBirdRegister 이벤트에서 확인할 수 있다.
     *
     * @param masternode 참여한 마스터노드의 주소
     */
    function getInfoByMasternode(address masternode)
    public
    view
    returns (address participant, uint40 round, uint16 index, uint40 nonce, bool canceled, uint256 collateral)
    {
        Participation memory info = masternodeInfo[masternode];

        participant = info.participant;
        round = info.round;
        index = info.index;
        nonce = info.nonce;
        if(info.canceled > 0) {
            canceled = true;
        }
        collateral = info.collateral;
    }

    /**
     * @dev 참여자의 주소와, 얼리버드 참여 횟수로 마스터노드 정보를 확인한다.
     *
     * @param participant 참여자의 주소
     * @param nonce 참여자의 참여 횟수
     */
    function getInfoByParticipant(address participant, uint32 nonce)
    public
    view
    returns (address masternode, uint32 round, uint16 index, bool canceled, uint256 collateral)
    {
        masternode = getMasternodeAddress(participant, nonce);

        Participation memory info = masternodeInfo[masternode];

        round = info.round;
        index = info.index;
        if(info.canceled > 0) {
            canceled = true;
        }
        collateral = info.collateral;
    }


    /**
     * @dev 참여 정보를 이용해서 마스터노드의 주소를 새로 생성한다.
     *
     * @param participant 참여자의 주소
     * @param nonce 참여자의 참여 횟수
     */
    function getMasternodeAddress(address participant, uint256 nonce)
    public
    pure
    returns (address)
    {
        return address(ripemd160(abi.encodePacked(keccak256(abi.encodePacked(participant, nonce)))));
    }


    function getPeriodOfRound(uint32 round)
    public
    pure
    returns (uint256 ebStart, uint256 ebEndMnStart, uint256 mnEnd)
    {
        require(round > 0);

        ebStart = uint256(round - 1).mul(PERIOD_MASTERNODE);
        ebEndMnStart = ebStart.add(BLOCKS_PER_DAY);
        mnEnd = ebEndMnStart.add(PERIOD_MASTERNODE);
    }





    function init (address[] _owners, uint16 _required, address worker)
    public
    validRequirement(_owners.length, _required)
    emptyOwner()
    {
        for (uint i = 0; i < _owners.length; i++) {
            isOwner[_owners[i]] = true;
        }

        owners = _owners;
        required = _required;

        _worker = worker;
        _platform = worker;

        foundationAccount = 0x3affc7a364d5c725ce33be33aff9673a7dfeab64;
    }


    function registerGeneral(address participant, address recipient)
    public
    payable
    onlyWorker
    registrableBlock                            // 신청 가능한 블록인지 확인
    validCollateral(COLLATERAL_GENERAL)         // 컨트렉트에 담보 금액이 충분한지 확인
    enoughMasternodeSpace(COLLATERAL_GENERAL)   // 해당 담보금액의 마스터노드 수에 여유가 있는지
    {
        earlyBirdRegister(participant, recipient, COLLATERAL_GENERAL);
    }

    function registerMajor (address participant, address recipient)
    public
    payable
    onlyWorker
    registrableBlock
    validCollateral(COLLATERAL_MAJOR)
    enoughMasternodeSpace(COLLATERAL_MAJOR)
    {
        earlyBirdRegister(participant, recipient, COLLATERAL_MAJOR);
    }

    function registerPrivate (address participant, address recipient)
    public
    payable
    onlyWorker
    registrableBlock
    validCollateral(COLLATERAL_PRIVATE)
    enoughMasternodeSpace(COLLATERAL_PRIVATE)
    {
        earlyBirdRegister(participant, recipient, COLLATERAL_PRIVATE);
    }




    /**
     * @dev 마스터노드 상품에 얼리버드로 참여한다.
     *
     * @param participant 참여자의 주소 (플랫폼의 입금 주소)
     * @param collateral 참여자의 담보금액 (50,000APIS, 200,000APIS, 500,000APIS)
     */
    function earlyBirdRegister(address participant, address recipient, uint256 collateral)
    internal
    {
        uint32 round = getRound();
        uint32 nonce = participationNonce[participant] + 1;
        uint16 index;
        address masternode = getMasternodeAddress(participant, nonce);

        // 마스터노드가 존재하면 진행하면 안된다
        require(masternodeInfo[masternode].participant == 0x0);

        if(collateral == COLLATERAL_GENERAL) {
            index = uint16(mnListGeneral[round].length);
            mnListGeneral[round].push(masternode);

        } else if(collateral == COLLATERAL_MAJOR) {
            index = uint16(mnListMajor[round].length);
            mnListMajor[round].push(masternode);

        } else if(collateral == COLLATERAL_PRIVATE) {
            index = uint16(mnListPrivate[round].length);
            mnListPrivate[round].push(masternode);
        }

        masternodeInfo[masternode] = Participation({
            participant : participant,
            round : round,
            nonce : nonce,
            index : index,
            canceled : 0,
            collateral : collateral
            });


        participationNonce[participant] = nonce;

        emit EarlyBirdRegister(participant, masternode, recipient, collateral);
    }


    function cancelMasternode(address masternode, bool withdrawal)
    public
    onlyWorker
    platformExist
    masternodeExist(masternode)
    cancelableMasternode(masternode)
    cancelableBlock(masternode)
    {
        Participation storage info = masternodeInfo[masternode];
        info.canceled = 1;
        uint16 index = info.index;


        if(info.collateral == COLLATERAL_GENERAL) {

            if(mnListGeneral[info.round][index] == masternode) {
                address lastGeneral = mnListGeneral[info.round][mnListGeneral[info.round].length - 1];

                mnListGeneral[info.round][index] = lastGeneral;
                masternodeInfo[lastGeneral].index = index;
                mnListGeneral[info.round].length = mnListGeneral[info.round].length - 1;
            }
        }

        else if(info.collateral == COLLATERAL_MAJOR) {

            if(mnListMajor[info.round][index] == masternode) {
                address lastMajor = mnListMajor[info.round][mnListMajor[info.round].length - 1];

                mnListMajor[info.round][index] = lastMajor;
                masternodeInfo[lastMajor].index = index;
                mnListMajor[info.round].length -= 1;
            }
        }

        else if(info.collateral == COLLATERAL_PRIVATE) {

            if(mnListPrivate[info.round][index] == masternode) {
                address lastPrivate = mnListPrivate[info.round][mnListPrivate[info.round].length - 1];

                mnListPrivate[info.round][index] = lastPrivate;
                masternodeInfo[lastPrivate].index = index;
                mnListPrivate[info.round].length -= 1;
            }
        }

        // 출금이 필요한 경우, 플랫폼 지갑으로 송금한다.
        if(withdrawal) {
            _platform.transfer(info.collateral);
        }



        emit MasternodeCancel(info.participant, masternode, info.collateral);
    }



    function sizeofGeneral(uint256 round)
    public
    view
    returns (uint256)
    {
        if(round == 0) {
            return mnListGeneral[getRound()].length;
        } else {
            return mnListGeneral[round].length;
        }
    }

    function sizeofMajor(uint256 round)
    public
    view
    returns (uint256)
    {
        if(round == 0) {
            return mnListMajor[getRound()].length;
        } else {
            return mnListMajor[round].length;
        }
    }

    function sizeofPrivate(uint256 round)
    public
    view
    returns (uint256)
    {
        if(round == 0) {
            return mnListPrivate[getRound()].length;
        } else {
            return mnListPrivate[round].length;
        }
    }





    function getNodeGeneral(uint256 round, uint256 index)
    public
    view
    returns (address)
    {
        if(round == 0) {
            return mnListGeneral[getRound()][index];
        } else {
            return mnListGeneral[round][index];
        }
    }

    function getNodeMajor(uint256 round, uint256 index)
    public
    view
    returns (address)
    {
        if(round == 0) {
            return mnListMajor[getRound()][index];
        } else {
            return mnListMajor[round][index];
        }
    }

    function getNodePrivate(uint256 round, uint256 index)
    public
    view
    returns (address)
    {
        if(round == 0) {
            return mnListPrivate[getRound()][index];
        } else {
            return mnListPrivate[round][index];
        }
    }







    /**
     * @dev 플랫폼의 마스터노드 운용 지갑 주소를 설정한다. 출금은 이 주소로만 이루어진다.
     */
    function setPlatform(address platform)
    public
    ownerExists(msg.sender)
    notNull(platform)
    {
        _platform = platform;
    }

    function setWorker(address worker)
    public
    ownerExists(msg.sender)
    notNull(worker)
    {
        _worker = worker;
    }


    function withdrawal(uint256 amount)
    public
    onlyWorker
    enoughBalance(amount)
    {
        _platform.transfer(amount);
    }
}